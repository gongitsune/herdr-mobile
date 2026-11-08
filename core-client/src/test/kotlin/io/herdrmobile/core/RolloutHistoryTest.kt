package io.herdrmobile.core

import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.*

class RolloutHistoryTest {
    @Test fun revertsReadOnlyTheByteAndOrdinalCheckedPrefixOfTheNamedBase():Unit=runBlocking {
        val meta="""{"ordinal":0,"type":"session_meta","payload":{"id":"thread","history_mode":"paginated"}}"""
        val user="""{"ordinal":1,"type":"item_completed","payload":{"turn_id":"turn","item":{"type":"UserMessage","id":"u","content":[{"type":"text","text":"Keep me"}]}}}"""
        val removed="""{"ordinal":2,"type":"item_completed","payload":{"turn_id":"turn","item":{"type":"AgentMessage","id":"a","content":[{"type":"Text","text":"Rolled back"}]}}}"""
        val prefix=meta+"\n"+user+"\n"
        val file=prefix+removed+"\n"
        val peer=FakeCoreTransport()
        peer.intercept={ request ->
            when(FakeCoreTransport.method(request)) {
                "machine.exec" -> {
                    val encoded=Base64.getEncoder().encodeToString("\"/fixture/base.jsonl\"".toByteArray())
                    peer.respond(request,"""{"code":0,"stdout":"$encoded","stderr":"","truncated":false,"timed_out":false}"""); true
                }
                "file.read" -> {
                    peer.respond(request,buildJsonObject { put("text",file); put("first",1); put("last",3); put("lines",3) }.toString()); true
                }
                else -> false
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
        try {
            client.connect()
            fun header(bytes:Int,ordinal:Int)=HcJson.parseToJsonElement("""{"type":"session_meta","payload":{"id":"thread","history_mode":"paginated","history_base":{"thread_id":"base-rollout","end_byte_offset":$bytes,"end_ordinal_exclusive":$ordinal}}}""").obj()
            val binding=TranscriptBinding(AgentKind.CODEX,"thread","/fixture/current.jsonl","process")
            val good=client.rolloutHistory(binding,header(prefix.toByteArray().size,2))
            assertTrue(good.second)
            assertEquals(listOf(meta,user),good.first)
            assertFalse(client.rolloutHistory(binding,header(prefix.toByteArray().size-1,2)).second)
            assertFalse(client.rolloutHistory(binding,header(prefix.toByteArray().size,3)).second)
        } finally { client.close(); scope.cancel() }
    }
}
