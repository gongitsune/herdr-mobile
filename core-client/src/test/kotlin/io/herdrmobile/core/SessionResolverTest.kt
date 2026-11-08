package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.transcript.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.*

class SessionResolverTest {
    @Test fun codexNeedsExactlyOneRootThreadAndRejectsUnmatchedWriterLocks():Unit=runBlocking {
        val terminal=Terminal(address="t1",state="live",pid=11,argv=listOf("codex"),
            foreground=Foreground(pid=11,name="codex",argv=listOf("codex")))
        for ((files,locks,expected) in listOf(
            Triple(listOf("root","child"),emptyList(),"root"),
            Triple(listOf("root","other"),emptyList(),null),
            Triple(listOf("root"),listOf("other"),null))) {
            val peer=FakeCoreTransport()
            peer.intercept={ r ->
                val p=r["params"].obj()
                when(FakeCoreTransport.method(r)) {
                    "machine.exec" -> {
                        val data=buildJsonObject {
                            put("process_key","11:1:11:1")
                            put("open_files",JsonArray(files.map { JsonPrimitive("/fixture/$it.jsonl") }))
                            put("lock_ids",JsonArray(locks.map(::JsonPrimitive)))
                        }
                        val encoded=Base64.getEncoder().encodeToString(data.toString().toByteArray())
                        peer.respond(r,"""{"code":0,"stdout":"$encoded","stderr":"","truncated":false,"timed_out":false}"""); true
                    }
                    "file.read" -> {
                        val id=p.text("path").substringAfterLast('/').substringBefore('.')
                        val head="""{"type":"session_meta","payload":{"id":"$id","session_id":"${if(id=="child") "root" else id}","history_mode":"paginated"}}"""
                        peer.respond(r,buildJsonObject { put("text",head+"\n"); put("first",1); put("last",1); put("lines",1) }.toString()); true
                    }
                    else -> false
                }
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
            try { client.connect(); assertEquals(expected,SessionResolver(client).resolve(terminal).binding?.sessionId) }
            finally { client.close(); scope.cancel() }
        }
    }
}
