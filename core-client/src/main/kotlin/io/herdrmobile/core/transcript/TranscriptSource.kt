package io.herdrmobile.core.transcript

import io.herdrmobile.core.*
import io.herdrmobile.core.files.*
import io.herdrmobile.core.model.*
import io.herdrmobile.core.rpc.HcError
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/** Reads agent-owned JSONL through core RPC; never writes transcripts, documents, labels or input. */
class TranscriptSource(
    private val client:HcClient,
    private val terminalId:String,
    scope:CoroutineScope,
    private val pollIntervalMs:Long=1500,
):Closeable {
    private val owned=CoroutineScope(scope.coroutineContext+SupervisorJob(scope.coroutineContext[Job]))
    private val store=TerminalStore(client,owned)
    private val resolver=SessionResolver(client)
    private val lock=Mutex()
    private val mutableState=MutableStateFlow<TranscriptState>(TranscriptState.Loading)
    val state:StateFlow<TranscriptState> = mutableState.asStateFlow()
    private val mutableItems=MutableStateFlow<List<ChatItem>>(emptyList())
    val items:StateFlow<List<ChatItem>> = mutableItems.asStateFlow()
    private var binding:TranscriptBinding?=null
    private var selected:TranscriptBinding?=null
    private val previewed=mutableSetOf<TranscriptCandidate>()
    private val lines=mutableListOf<String>()
    private var nextLine=1L
    private var checkpoint:String?=null
    private var bytes=0L
    private var resetOnReconnect=false
    private var canFollow=false
    private var historyPrefix:List<String> = emptyList()
    private var historyComplete=true
    private var historyLoaded=false
    init {
        require(pollIntervalMs>0)
        owned.launch {
            var lastConnection: Any?=null
            combine(store.snapshotConnection,store.terminals) { connection,terminals -> connection to terminals.find { it.id==terminalId } }
                .distinctUntilChangedBy { (connection,t) -> listOf(connection,t?.foreground,t?.pid,t?.status,t?.turn,t?.labels,t?.state,t?.reason) }
                .collectLatest { (connection,terminal) ->
                    lock.withLock {
                        if(connection !== lastConnection) resetOnReconnect=true
                        lastConnection=connection
                        if(connection==null || terminal==null) {
                            canFollow=false
                            mutableState.value=TranscriptState.Unavailable("Terminal is not connected")
                        }
                    }
                    if(connection!=null && terminal!=null) refresh()
                }
        }
        owned.launch {
            while(isActive) { delay(pollIntervalMs); update(rebind=false) }
        }
    }
    suspend fun refresh() { update(rebind=true) }
    private suspend fun update(rebind:Boolean) = withContext(Dispatchers.Default) { lock.withLock {
        if(!owned.isActive || (!rebind && !canFollow)) return@withLock
        val terminal=store.terminals.value.find { it.id==terminalId } ?: return@withLock
        try {
            if(rebind) {
                val evidence=resolver.resolve(terminal)
                val chosen=selected?.takeIf { it.processKey==evidence.processKey }
                if(selected!=null && chosen==null) { selected=null; previewed.clear(); mutableItems.value=emptyList() }
                val resolved=evidence.binding ?: chosen
                if(resolved==null) {
                    canFollow=false
                    mutableState.value=TranscriptState.Unavailable(evidence.reason)
                    if(binding?.confirmed==true) { binding=null; mutableItems.value=emptyList() }
                    return@withLock
                }
                if(evidence.binding!=null && chosen!=null && evidence.binding.path!=chosen.path) selected=null
                canFollow=true
                if(resolved.path!=binding?.path || resolved.processKey!=binding?.processKey || resetOnReconnect) {
                    binding=resolved; resetReader(); mutableItems.value=emptyList(); resetOnReconnect=false
                } else binding=resolved
            }
            val current=binding ?: return@withLock
            readAppend(current)
            if(current.kind==AgentKind.CODEX && !historyLoaded && lines.isNotEmpty()) {
                val header=parseLines(lines.take(1)).firstOrNull()
                if(header!=null) {
                    val history=client.rolloutHistory(current,header)
                    historyPrefix=history.first; historyComplete=history.second; historyLoaded=true
                }
            }
            if(historyPrefix.sumOf { it.toByteArray().size.toLong()+1 }+bytes>32L*1024*1024) {
                historyPrefix=emptyList(); historyComplete=false
            }
            val rows=withContext(Dispatchers.Default) { projectTranscript(current.kind,historyPrefix+lines,terminal.status?.state=="blocked") }
            mutableItems.value=if(historyComplete) rows else listOf(ChatItem.TurnMarker(current.sessionId+"/history","Earlier rollout history is unavailable"))+rows
            if(!historyComplete) mutableState.value=TranscriptState.Ready(current,false)
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { mutableState.value=TranscriptState.Failed(connectionError(e)) }
    } }
    private fun resetReader() { nextLine=1; checkpoint=null; bytes=0; lines.clear(); historyPrefix=emptyList(); historyLoaded=false; historyComplete=true }
    private suspend fun readAppend(current:TranscriptBinding) {
        var overlap=checkpoint!=null
        val offset=if(nextLine>1) nextLine-1 else 1
        var chunk=try { client.readChunk(current.path,offset=offset,limit=2000,on=current.machine) }
        catch(e:HcError) {
            if(e.kind!="invalid_params" || offset==1L) throw e
            // core rejects an offset beyond EOF before it can report the new line count.
            resetReader(); overlap=false
            client.readChunk(current.path,offset=1,limit=2000,on=current.machine)
        }
        var complete=chunk.text.split('\n').dropLast(1)
        if(chunk.lines<nextLine-1 || (checkpoint!=null && complete.firstOrNull()!=checkpoint)) {
            resetReader(); overlap=false
            chunk=client.readChunk(current.path,offset=1,limit=2000,on=current.machine)
            complete=chunk.text.split('\n').dropLast(1)
        }
        val append=if(overlap) complete.drop(1) else complete
        for(line in append) {
            bytes+=line.toByteArray().size+1
            if(bytes>32L*1024*1024 || lines.size>=100_000) throw HcError("unsupported","Transcript exceeds the 32MiB/100000-line display limit")
            lines+=line; nextLine++
        }
        checkpoint=lines.lastOrNull()
        val caughtUp=nextLine>chunk.lines && (chunk.text.isEmpty() || chunk.text.endsWith('\n'))
        mutableState.value=TranscriptState.Ready(current,caughtUp)
    }
    suspend fun candidates():List<TranscriptCandidate> = lock.withLock {
        val terminal=store.terminals.value.find { it.id==terminalId } ?: return@withLock emptyList()
        val evidence=resolver.resolve(terminal,listCandidates=true)
        mutableState.value=TranscriptState.Unavailable(evidence.reason,evidence.candidates)
        evidence.candidates
    }
    suspend fun preview(candidate:TranscriptCandidate):List<ChatItem> = lock.withLock {
        val terminal=store.terminals.value.find { it.id==terminalId } ?: throw HcError("io","Terminal is not connected")
        val evidence=resolver.resolve(terminal,listCandidates=true)
        require(candidate in evidence.candidates && candidate.processKey==evidence.processKey) { "Candidate no longer belongs to this agent process" }
        val chunk=client.readChunk(candidate.path,limit=2000,on=candidate.machine)
        val rows=chunk.text.split('\n').dropLast(1)
        val items=withContext(Dispatchers.Default) { projectTranscript(candidate.kind,rows,blocked=false) }
        if(items.isNotEmpty()) previewed+=candidate
        items
    }
    /** The caller must ask the user to confirm the preview; no selection is persisted to core. */
    suspend fun select(candidate:TranscriptCandidate) {
        lock.withLock {
            require(candidate in previewed) { "Preview the conversation before confirming it" }
            val terminal=store.terminals.value.find { it.id==terminalId } ?: throw HcError("io","Terminal is not connected")
            val evidence=resolver.resolve(terminal,listCandidates=true)
            require(candidate in evidence.candidates && evidence.processKey==candidate.processKey) { "Agent process changed" }
            selected=TranscriptBinding(candidate.kind,candidate.sessionId,candidate.path,candidate.processKey,candidate.machine,true)
            binding=null
        }
        refresh()
    }
    override fun close() { owned.cancel() }
}
