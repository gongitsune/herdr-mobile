package io.herdrmobile.core.transcript

import io.herdrmobile.core.*
import io.herdrmobile.core.files.*
import io.herdrmobile.core.model.*
import io.herdrmobile.core.rpc.HcError
import io.herdrmobile.core.workspace.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal data class SessionEvidence(val processKey:String,val binding:TranscriptBinding?,val candidates:List<TranscriptCandidate>,val reason:String)
internal class SessionResolver(private val client:HcClient) {
    private val storeScript=checkNotNull(javaClass.getResourceAsStream("/io/herdrmobile/core/transcript/codex_store.py")).bufferedReader().use { it.readText() }
    private val retained=mutableMapOf<String,Pair<TranscriptBinding,Long>>()
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
        if(kind==AgentKind.CLAUDE_CODE) {
            val exact=info["binding"].obj(); val id=exact.text("session_id"); val path=exact.text("path")
            if(id.isNotBlank() && path.isNotBlank()) {
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
        if(kind==AgentKind.CODEX && binding==null) {
            val newest=stored.filter { it["same_cwd"]?.jsonPrimitive?.booleanOrNull==true }.maxOfOrNull { it["created_ms"]?.jsonPrimitive?.longOrNull ?: 0L } ?: 0L
            val previous=retained[terminal.id]
            if(previous!=null && previous.first.processKey==process && newest<=previous.second) binding=previous.first
            if(binding==null) {
                val transcripts=mutableListOf<CodexScreenTranscript>()
                for(row in stored) {
                    val path=row.text("path")
                    try {
                        val first=client.readChunk(path,limit=1,on=machine)
                        var offset=maxOf(1L,first.lines-1999)
                        var chunk=client.readChunk(path,offset=offset,limit=2000,on=machine)
                        // file.read is paginated by lines; walk toward EOF, retaining only a bounded tail.
                        var tail=""
                        while(true) {
                            tail=(tail+chunk.text).takeLast(1024*1024)
                            if(chunk.last>=chunk.lines) break
                            offset=chunk.last+1
                            chunk=client.readChunk(path,offset=offset,limit=2000,on=machine)
                        }
                        val raw=tail.toByteArray()
                        val bounded=if(raw.size>1024*1024) raw.copyOfRange(raw.size-1024*1024,raw.size).decodeToString().substringAfter('\n', "") else tail
                        transcripts+=codexScreenTranscript(path,bounded.lineSequence().toList())
                    } catch(e:CancellationException) { throw e } catch(_:HcError) { }
                }
                // An unread competitor may own the displayed answer; never match an incomplete inventory.
                val screenPath=if(stored.isNotEmpty() && transcripts.size==stored.size) matchCodexScreen(client.read(terminal.id),transcripts) else null
                var chosen=stored.singleOrNull { it.text("path")==screenPath }
                if(chosen==null) {
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
                chosen?.let { binding=TranscriptBinding(kind,it.text("session_id"),it.text("path"),process,machine) }
            }
            binding?.let { retained[terminal.id]=it to newest }
            if(binding==null) retained.remove(terminal.id)
        }
        val candidates=mutableListOf<TranscriptCandidate>()
        for(v in stored) candidates+=TranscriptCandidate(kind,v.text("session_id"),v.text("path"),process,machine)
        for(value in info["candidates"].arr().take(24)) {
            val v=value.obj(); val path=v.text("path"); val h=header(path) ?: continue
            val id=if(kind==AgentKind.CLAUDE_CODE) h.text("sessionId") else h["payload"].obj().text("id")
            if(id.isNotBlank() && !h.flag("isSidechain") && h.text("agentId").isEmpty() && (kind!=AgentKind.CODEX || codexRoot(h["payload"].obj())))
                candidates+=TranscriptCandidate(kind,id,path,process,machine)
        }
        return SessionEvidence(process,binding,candidates,info.text("reason").ifBlank { "Cannot determine a unique session" })
    }
}

internal fun codexRoot(p:JsonObject):Boolean = p.text("parent_thread_id").isEmpty() && p.text("agent_role").isEmpty() &&
    (p["thread_source"]==null || p["thread_source"]==JsonNull || p.text("thread_source")=="user") && !p["source"].toString().contains("subagent",ignoreCase=true)
