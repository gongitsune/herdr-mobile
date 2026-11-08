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
    private val script=checkNotNull(javaClass.getResourceAsStream("/io/herdrmobile/core/transcript/discover.py")).bufferedReader().use { it.readText() }
    suspend fun resolve(terminal:Terminal,listCandidates:Boolean=false):SessionEvidence {
        val agent=AgentDetector.detect(terminal)
        if(agent?.kind !in setOf(AgentKind.CLAUDE_CODE,AgentKind.CODEX)) return SessionEvidence("",null,emptyList(),"No supported agent is running")
        val foreground=terminal.foreground?.pid
        val parent=terminal.pid
        if(foreground==null || parent==null) return SessionEvidence("",null,emptyList(),"Waiting for an active foreground process")
        val machine=machineOf(terminal.id)
        val kind=checkNotNull(agent).kind
        val result=client.execRead(listOf("python3","-c",script,kind.name,foreground.toString(),parent.toString(),terminal.cwd,if(listCandidates) "candidates" else "probe"),on=machine)
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
                    !p["source"].toString().contains("subagent",ignoreCase=true)) roots+=id to path
            }
            val byThread=roots.groupBy { it.first }
            val locks=info["lock_ids"].arr().map { it.str() }
            if(byThread.size==1 && locks.all { it in byThread }) {
                val (id,path)=roots.maxBy { it.second.substringAfterLast('/') }
                binding=TranscriptBinding(kind,id,path,process,machine)
            }
        }
        val candidates=mutableListOf<TranscriptCandidate>()
        for(value in info["candidates"].arr().take(24)) {
            val v=value.obj(); val path=v.text("path"); val h=header(path) ?: continue
            val id=if(kind==AgentKind.CLAUDE_CODE) h.text("sessionId") else h["payload"].obj().text("id")
            if(id.isNotBlank() && !h.flag("isSidechain") && h.text("agentId").isEmpty())
                candidates+=TranscriptCandidate(kind,id,path,process,machine)
        }
        return SessionEvidence(process,binding,candidates,info.text("reason").ifBlank { "Cannot determine a unique session" })
    }
}
