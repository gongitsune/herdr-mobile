package io.herdrmobile.core.transcript

import io.herdrmobile.core.*
import io.herdrmobile.core.files.*
import io.herdrmobile.core.model.*
import io.herdrmobile.core.rpc.HcError
import io.herdrmobile.core.workspace.AgentKind
import io.herdrmobile.core.workspace.AgentDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
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
    private val cache=client.transcriptCache
    private val lock=Mutex()
    private val mutableState=MutableStateFlow<TranscriptState>(TranscriptState.Loading)
    val state:StateFlow<TranscriptState> = mutableState.asStateFlow()
    private val mutableItems=MutableStateFlow<List<ChatItem>>(emptyList())
    val items:StateFlow<List<ChatItem>> = mutableItems.asStateFlow()
    private val mutableActivity=MutableStateFlow<TurnActivity?>(null)
    val activity:StateFlow<TurnActivity?> = mutableActivity.asStateFlow()
    private val mutableSession=MutableStateFlow<SessionInfo?>(null)
    val session:StateFlow<SessionInfo?> = mutableSession.asStateFlow()
    private val polling=TranscriptPolling(pollIntervalMs)
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val nudged=AtomicBoolean()
    private var projectedRevision=-1L
    private var revision=0L
    private var projectedBlocked:Boolean?=null
    private var projectedActivity:TurnActivity?=null
    private var cachedLines:List<String> = emptyList()
    private var cachedRevision=-1L
    private var lastTail:Pair<Int,Int>?=null
    private var historyBytes=0L
    private var binding:TranscriptBinding?=null
    private var selected:TranscriptBinding?=null
    private val previewed=mutableSetOf<TranscriptCandidate>()
    private val lines=mutableListOf<String>()
    private var nextLine=1L
    private var checkpoint:String?=null
    private var bytes=0L
    private var cachedProcess:TranscriptProcess?=null
    private var needsVerification=false
    private var canFollow=false
    private var waitingForConversation=false
    private var historyPrefix:List<String> = emptyList()
    private var historyComplete=true
    private var historyLoaded=false
    init {
        require(pollIntervalMs>0)
        cache.latest(terminalId)?.let(::restore)
        owned.launch {
            var firstRecord=true
            combine(store.snapshotConnection,store.terminals) { connection,terminals -> connection to terminals.find { it.id==terminalId } }
                .distinctUntilChangedBy { (connection,t) -> listOf(connection,t?.foreground,t?.pid,t?.status,t?.turn,t?.labels,t?.state,t?.reason) }
                .collectLatest { (connection,terminal) ->
                    polling.changed()
                    wake.trySend(Unit)
                    lock.withLock {
                        if(connection==null || terminal==null) {
                            canFollow=false
                            // Waiting for the first snapshot is not a completed negative resolution.
                            // A known conversation remains visible throughout a transport interruption.
                        }
                    }
                    if(connection!=null && terminal!=null) {
                        val useCached=firstRecord && cachedProcess==TranscriptProcess.of(terminal)
                        firstRecord=false
                        update(rebind=true,useCachedBinding=useCached)
                    }
                }
        }
        owned.launch {
            while(isActive) {
                val status=store.terminals.value.find { it.id==terminalId }?.status?.state
                val signalled=withTimeoutOrNull(polling.delayMs(status)) { wake.receive(); true } ?: false
                val immediate=nudged.getAndSet(false)
                if(!signalled || immediate) update(rebind=immediate)
            }
        }
    }
    /** Schedule an immediate read after sending, and keep the next 15s of polling fast. */
    fun nudge() {
        if(!owned.isActive) return
        polling.changed()
        nudged.set(true)
        wake.trySend(Unit)
    }
    suspend fun refresh() { update(rebind=true) }
    private suspend fun update(rebind:Boolean,useCachedBinding:Boolean=false) = withContext(Dispatchers.Default) { lock.withLock {
        if(!owned.isActive) return@withLock
        val terminal=store.terminals.value.find { it.id==terminalId } ?: return@withLock
        val connection=store.snapshotConnection.value ?: return@withLock
        val process=TranscriptProcess.of(terminal)
        if(cachedProcess!=null && cachedProcess!=process) {
            cache.invalidate(terminalId)
            binding=null; selected=null; previewed.clear(); resetReader()
            mutableItems.value=emptyList(); mutableActivity.value=null
            mutableState.value=TranscriptState.Loading
            cachedProcess=null
        }
        val kind=AgentDetector.detect(terminal)?.kind
        val codex=kind==AgentKind.CODEX
        if(kind !in setOf(AgentKind.CLAUDE_CODE,AgentKind.CODEX)) waitingForConversation=false
        if(!rebind && !needsVerification && !canFollow && !codex && !waitingForConversation) return@withLock
        try {
            if(!useCachedBinding && (rebind || needsVerification || codex || waitingForConversation)) {
                val evidence=resolver.resolve(terminal)
                needsVerification=false
                val chosen=selected?.takeIf { it.processKey==evidence.processKey }
                if(selected!=null && chosen==null) { selected=null; previewed.clear(); mutableItems.value=emptyList() }
                val resolved=if(evidence.notStarted!=null) null else evidence.binding ?: chosen
                if(resolved==null) {
                    cache.invalidate(terminalId)
                    canFollow=false
                    mutableSession.value=null
                    projectedRevision=-1
                    mutableActivity.value=null.withTerminal(terminal)
                    if(evidence.notStarted!=null) {
                        showNotStarted(evidence.notStarted,terminal)
                    } else {
                        mutableState.value=TranscriptState.Unavailable(evidence.reason,evidence.candidates)
                        if(codex || binding?.confirmed==true) { binding=null; mutableItems.value=emptyList() }
                    }
                    return@withLock
                }
                if(evidence.binding!=null && chosen!=null && evidence.binding.path!=chosen.path) selected=null
                waitingForConversation=false
                canFollow=true
                if(resolved.path!=binding?.path || resolved.processKey!=binding?.processKey) {
                    cache.invalidateProjections(terminalId)
                    binding=resolved; resetReader(); mutableItems.value=emptyList()
                } else binding=resolved
            }
            cachedProcess=process
            canFollow=true
            val current=binding ?: return@withLock
            val caughtUp=readAppend(current)
            if(current.kind==AgentKind.CODEX && !historyLoaded && lines.isNotEmpty()) {
                val header=parseLines(lines.take(1)).firstOrNull()
                if(header!=null) {
                    val history=client.rolloutHistory(current,header)
                    historyPrefix=history.first; historyComplete=history.second; historyLoaded=true
                    historyBytes=historyPrefix.sumOf { it.toByteArray().size.toLong()+1 }
                    revision++
                }
            }
            if(historyBytes+bytes>32L*1024*1024 && historyPrefix.isNotEmpty()) {
                historyPrefix=emptyList(); historyBytes=0; historyComplete=false; revision++
            }
            val blocked=terminal.status?.state=="blocked"
            // Status-only changes may hide/reveal questions. Unchanged polling does no JSON parsing.
            if(projectedRevision!=revision || projectedBlocked!=blocked) {
                val rows=projectTranscript(current.kind,historyPrefix+lines,blocked)
                if(projectedRevision!=revision) {
                    val next=projectSession(current.kind,historyPrefix+lines)
                    val previous=mutableSession.value?.limits
                    // Appending unrelated lines must not change the observation time or emit a new value.
                    mutableSession.value=if(next?.limits!=null && previous!=null &&
                        next.limits.plan==previous.plan && next.limits.windows==previous.windows)
                        next.copy(limits=previous) else next
                }
                currentCoroutineContext().ensureActive()
                if(!owned.isActive) return@withLock
                projectedActivity=turnActivity(rows)
                mutableItems.value=if(historyComplete) rows else listOf(ChatItem.TurnMarker(current.sessionId+"/history","Earlier rollout history is unavailable"))+rows
                projectedRevision=revision; projectedBlocked=blocked
            }
            mutableActivity.value=projectedActivity.withTerminal(terminal)
            val ready=TranscriptState.Ready(current,caughtUp && historyComplete)
            // Save before publishing Ready, so closing/reopening immediately observes a full checkpoint.
            if(cachedRevision!=revision) { cachedLines=lines.toList(); cachedRevision=revision }
            cache.put(terminal,CachedTranscript(process,current,ready,mutableItems.value,mutableActivity.value,
                mutableSession.value,cachedLines,nextLine,checkpoint,bytes,lastTail,historyPrefix,
                historyComplete,historyLoaded,historyBytes,projectedBlocked,projectedActivity))
            mutableState.value=ready
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            if(!connection.isClosed && client.connection===connection)
                mutableState.value=TranscriptState.Failed(connectionError(e))
        }
    } }
    private fun restore(value:CachedTranscript) {
        binding=value.binding
        selected=value.binding.takeIf { it.confirmed }
        cachedProcess=value.process; needsVerification=true; canFollow=true
        mutableItems.value=value.items; mutableActivity.value=value.activity; mutableSession.value=value.session
        lines.addAll(value.lines); nextLine=value.nextLine; checkpoint=value.checkpoint
        bytes=value.bytes; lastTail=value.lastTail; historyPrefix=value.historyPrefix
        historyComplete=value.historyComplete; historyLoaded=value.historyLoaded; historyBytes=value.historyBytes
        projectedBlocked=value.projectedBlocked; projectedActivity=value.projectedActivity
        projectedRevision=revision
        cachedLines=value.lines; cachedRevision=revision
        mutableState.value=value.ready
    }
    private fun resetReader() {
        mutableSession.value=null
        nextLine=1; checkpoint=null; bytes=0; lines.clear(); historyPrefix=emptyList()
        historyLoaded=false; historyComplete=true; historyBytes=0; lastTail=null
        projectedActivity=null; revision++
    }
    private fun showNotStarted(kind:AgentKind,terminal:Terminal) {
        // Keep retrying through transient partial writes/ambiguous evidence.
        waitingForConversation=true; canFollow=false
        cache.invalidate(terminalId)
        selected=null; previewed.clear(); binding=null; resetReader()
        projectedRevision=-1; mutableItems.value=emptyList()
        mutableActivity.value=null.withTerminal(terminal)
        mutableState.value=TranscriptState.NotStarted(kind)
    }
    /** Drain pages in one update. A partial EOF is retried on the next poll, never spun on. */
    private suspend fun readAppend(current:TranscriptBinding):Boolean {
        var resets=0
        var verifyCheckpoint=true
        while(true) {
            currentCoroutineContext().ensureActive()
            val overlap=checkpoint!=null && verifyCheckpoint
            val offset=if(overlap) nextLine-1 else nextLine
            val before=nextLine
            val chunk=try { client.readChunk(current.path,offset=offset,limit=2000,on=current.machine) }
            catch(e:HcError) {
                if(e.kind!="invalid_params" || offset==1L) throw e
                if(++resets>3) throw HcError("io","Transcript changed repeatedly while reading")
                resetReader(); polling.changed(); wake.trySend(Unit); continue
            }
            val complete=chunk.text.split('\n').dropLast(1)
            if(chunk.lines<nextLine-1 || (overlap && complete.firstOrNull()!=checkpoint)) {
                if(++resets>3) throw HcError("io","Transcript changed repeatedly while reading")
                resetReader(); polling.changed(); wake.trySend(Unit); continue
            }
            verifyCheckpoint=false
            val append=if(overlap) complete.drop(1) else complete
            for(line in append) {
                val size=line.toByteArray().size+1
                if(bytes+size>32L*1024*1024 || lines.size>=100_000) throw HcError("unsupported","Transcript exceeds the 32MiB/100000-line display limit")
                bytes+=size; lines+=line; nextLine++
            }
            if(append.isNotEmpty()) { revision++; polling.changed(); wake.trySend(Unit) }
            checkpoint=lines.lastOrNull()
            val tail=chunk.text.substringAfterLast('\n')
            val tailSignature=tail.length to tail.hashCode()
            if(tail.isNotEmpty() && tailSignature!=lastTail) { polling.changed(); wake.trySend(Unit) }
            lastTail=tailSignature
            val caughtUp=nextLine>chunk.lines && (chunk.text.isEmpty() || chunk.text.endsWith('\n'))
            if(caughtUp) return true
            if(overlap && append.isEmpty() && tail.isEmpty() && nextLine<=chunk.lines) continue
            if(nextLine==before || (tail.isNotEmpty() && chunk.last>=chunk.lines)) return false
        }
    }
    suspend fun candidates():List<TranscriptCandidate> = lock.withLock {
        val terminal=store.terminals.value.find { it.id==terminalId } ?: return@withLock emptyList()
        val evidence=resolver.resolve(terminal,listCandidates=true)
        if(evidence.notStarted!=null) showNotStarted(evidence.notStarted,terminal)
        else mutableState.value=TranscriptState.Unavailable(evidence.reason,evidence.candidates)
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
    override fun close() { owned.cancel(); mutableActivity.value=null; mutableSession.value=null }
}
