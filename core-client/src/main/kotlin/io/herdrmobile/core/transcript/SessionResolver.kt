package io.herdrmobile.core.transcript

import io.herdrmobile.core.*
import io.herdrmobile.core.files.*
import io.herdrmobile.core.model.*
import io.herdrmobile.core.rpc.HcError
import io.herdrmobile.core.workspace.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

internal data class SessionEvidence(val processKey:String,val binding:TranscriptBinding?,val candidates:List<TranscriptCandidate>,val reason:String,
                                    val notStarted:AgentKind?=null)
internal class SessionResolver(private val client:HcClient) {
    private val storeScript=checkNotNull(javaClass.getResourceAsStream("/io/herdrmobile/core/transcript/codex_store.py")).bufferedReader().use { it.readText() }
    private val cache=client.transcriptCache
    private val script=storeScript+"\n"+checkNotNull(javaClass.getResourceAsStream("/io/herdrmobile/core/transcript/discover.py")).bufferedReader().use { it.readText() }
    suspend fun resolve(terminal:Terminal,listCandidates:Boolean=false):SessionEvidence {
        val agent=AgentDetector.detect(terminal)
        if(agent?.kind !in setOf(AgentKind.CLAUDE_CODE,AgentKind.CODEX)) return SessionEvidence("",null,emptyList(),"No supported agent is running")
        val foreground=terminal.foreground?.pid
        val parent=terminal.pid
        if(foreground==null || parent==null) return SessionEvidence("",null,emptyList(),"Waiting for an active foreground process")
        val machine=machineOf(terminal.id)
        val kind=checkNotNull(agent).kind
        val result=client.execRead(listOf("python3","-c",script,kind.name,foreground.toString(),parent.toString(),terminal.foreground?.cwd?.ifBlank { terminal.cwd } ?: terminal.cwd,if(listCandidates) "candidates" else "probe",terminal.title),on=machine)
        if(result.code!=0 || result.timedOut || result.truncated) return SessionEvidence("",null,emptyList(),"Host evidence requires Python 3 and readable Linux /proc")
        val info=HcJson.parseToJsonElement(result.stdout.decodeToString()).obj()
        val process=info.text("process_key")
        if(process.isEmpty()) return SessionEvidence("",null,emptyList(),info.text("reason"))
        suspend fun header(path:String):JsonObject? {
            if(!path.startsWith('/') || !path.endsWith(".jsonl")) return null
            return try { parseLines(client.readChunk(path,offset=1,limit=1,on=machine).text.lineSequence().toList()).firstOrNull() }
            catch(e:CancellationException) { throw e } catch(_:HcError) { null }
        }
        var binding:TranscriptBinding?=null
        var notStarted:AgentKind?=null
        var screen:String?=null
        if(kind==AgentKind.CLAUDE_CODE) {
            val exact=info["binding"].obj(); val id=exact.text("session_id"); val path=exact.text("path")
            if(info.flag("not_started")) notStarted=kind
            else if(id.isNotBlank() && path.isNotBlank()) {
                val heads=try { parseLines(client.readChunk(path,offset=1,limit=32,on=machine).text.lineSequence().toList()) }
                    catch(e:CancellationException) { throw e } catch(_:HcError) { emptyList() }
                val head=heads.firstOrNull { it.text("sessionId").isNotBlank() }
                val chain=heads.firstOrNull { it.text("type") in setOf("user","assistant","system") }
                if(head!=null && head.text("sessionId")==id && chain!=null && !chain.flag("isSidechain") && chain.text("agentId").isEmpty())
                    binding=TranscriptBinding(kind,id,path,process,machine)
            }
        } else {
            val roots=mutableListOf<Pair<String,String>>()
            for(value in info["open_files"].arr().take(8)) {
                val path=value.str(); val h=header(path) ?: continue; val p=h["payload"].obj()
                val id=p.text("id")
                if(h.text("type")=="session_meta" && id.isNotBlank() && p.text("session_id").ifEmpty { id }==id &&
                    codexRoot(p)) roots+=id to path
            }
            val byThread=roots.groupBy { it.first }
            val locks=info["lock_ids"].arr().map { it.str() }
            if(byThread.size==1 && locks.all { it in byThread }) {
                val (id,path)=roots.maxBy { it.second.substringAfterLast('/') }
                binding=TranscriptBinding(kind,id,path,process,machine)
            }
        }
        val stored=info["stored"].arr().map { it.obj() }
            .sortedByDescending { it["updated_at"]?.jsonPrimitive?.longOrNull ?: 0L }
        if(kind==AgentKind.CODEX && binding==null) {
            val newest=stored.filter { it["same_cwd"]?.jsonPrimitive?.booleanOrNull==true }.maxOfOrNull { it["created_ms"]?.jsonPrimitive?.longOrNull ?: 0L } ?: 0L
            val previous=cache.retained(terminal.id,process)
            if(previous!=null && previous.first.processKey==process && newest<=previous.second) binding=previous.first
            if(binding==null) {
                // A unique new thread with no possible peer owner needs no conversation reads.
                var chosen:JsonObject?=null
                run {
                    val started=info["process_started_ms"]?.jsonPrimitive?.longOrNull
                    val fresh=if(started==null) emptyList() else stored.filter { it["same_cwd"]?.jsonPrimitive?.booleanOrNull==true && (it["created_ms"]?.jsonPrimitive?.longOrNull ?: 0)>=started }
                    val only=fresh.singleOrNull()
                    if(only!=null) {
                        val created=only["created_ms"]!!.jsonPrimitive.long
                        val peers=client.list(all=true).filter {
                            it.id!=terminal.id && machineOf(it.id)==machine &&
                                (it.finished_ms==null || it.finished_ms>=created) &&
                                AgentDetector.detect(if(it.state=="finished") it.copy(state="live",reason=null) else it)?.kind==AgentKind.CODEX
                        }
                        // A terminal created after this thread could not have owned it. Earlier peers
                        // remain ambiguous, including peers whose foreground process start is unknown.
                        if(created==newest && peers.none { it.created_ms<=0 || it.created_ms<=created+999 }) chosen=only
                    }
                }
                if(chosen==null && stored.isNotEmpty()) {
                    screen=client.read(terminal.id)
                    val screenPath=stagedCodexScreenMatch(checkNotNull(screen),stored) { row ->
                        val path=row.text("path")
                        try {
                            val tail=client.execRead(listOf("python3","-c",CODEX_TAIL_SCRIPT,path),on=machine)
                            if(tail.code!=0 || tail.timedOut || tail.truncated || tail.stdout.size>CODEX_TAIL_BYTES) null
                            else codexScreenTranscript(path,tail.stdout.decodeToString().lineSequence().toList())
                        } catch(e:CancellationException) { throw e } catch(_:HcError) { null }
                    }
                    chosen=stored.singleOrNull { it.text("path")==screenPath }
                }
                chosen?.let { binding=TranscriptBinding(kind,it.text("session_id"),it.text("path"),process,machine) }
            }
            cache.retain(terminal.id,process,binding?.let { it to newest })
            if(binding==null && info.flag("no_new_threads") && info["process_started_ms"]?.jsonPrimitive?.longOrNull!=null &&
                info["open_files"].arr().isEmpty() && info["lock_ids"].arr().isEmpty() &&
                stored.none { it.flag("same_cwd") && (it["created_ms"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE)>=
                    info["process_started_ms"]!!.jsonPrimitive.long-1000 }) {
                if(codexScreenNotStarted(screen ?: client.read(terminal.id))) notStarted=kind
            }
        }
        val candidates=mutableListOf<TranscriptCandidate>()
        for(v in stored) candidates+=TranscriptCandidate(kind,v.text("session_id"),v.text("path"),process,machine)
        for(value in info["candidates"].arr().take(24)) {
            val v=value.obj(); val path=v.text("path"); val h=header(path) ?: continue
            val id=if(kind==AgentKind.CLAUDE_CODE) h.text("sessionId") else h["payload"].obj().text("id")
            if(id.isNotBlank() && !h.flag("isSidechain") && h.text("agentId").isEmpty() && (kind!=AgentKind.CODEX || codexRoot(h["payload"].obj())))
                candidates+=TranscriptCandidate(kind,id,path,process,machine)
        }
        return SessionEvidence(process,binding,candidates,info.text("reason").ifBlank { "Cannot determine a unique session" },notStarted)
    }
}

internal const val CODEX_TAIL_BYTES=256*1024
internal val CODEX_TAIL_SCRIPT="""
    import os, sys
    with open(sys.argv[1], 'rb') as f:
        size = f.seek(0, os.SEEK_END)
        start = max(0, size - $CODEX_TAIL_BYTES)
        f.seek(start)
        data = f.read($CODEX_TAIL_BYTES)
    if start:
        data = data.partition(b'\n')[2]
    sys.stdout.buffer.write(data)
""".trimIndent()

/** Inspect recent candidates first; never infer ownership from an unreadable candidate in a stage. */
internal suspend fun stagedCodexScreenMatch(screen:String,stored:List<JsonObject>,
    read:suspend (JsonObject)->CodexScreenTranscript?):String? = coroutineScope {
    val permits=Semaphore(4)
    suspend fun load(rows:List<JsonObject>)=rows.map { row -> async { permits.withPermit { read(row) } } }.awaitAll()
    val recent=load(stored.take(8))
    val first=if(recent.all { it!=null }) matchCodexScreen(screen,recent.filterNotNull()) else null
    if(first!=null) return@coroutineScope first
    val rest=load(stored.drop(8))
    val all=recent+rest
    if(all.any { it==null }) null else matchCodexScreen(screen,all.filterNotNull())
}

internal fun codexRoot(p:JsonObject):Boolean = p.text("parent_thread_id").isEmpty() && p.text("agent_role").isEmpty() &&
    (p["thread_source"]==null || p["thread_source"]==JsonNull || p.text("thread_source")=="user") && !p["source"].toString().contains("subagent",ignoreCase=true)
