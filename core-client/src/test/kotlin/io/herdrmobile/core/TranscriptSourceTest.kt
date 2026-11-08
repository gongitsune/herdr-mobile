package io.herdrmobile.core

import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.wire.Frame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TranscriptSourceTest {
    private val user="""{"type":"user","uuid":"u","parentUuid":null,"sessionId":"fixture-session","message":{"content":"Synthetic prompt"}}"""
    private val answer="""{"type":"assistant","uuid":"a","parentUuid":"u","sessionId":"fixture-session","message":{"id":"m","content":[{"type":"text","text":"Synthetic answer"}]}}"""
    @Test fun appendOnlyReadRereadsPartialTailWithoutDuplicatesAndResetsAfterTruncation():Unit=runBlocking {
        withTimeout(6000) {
            val peer=TranscriptPeer(user+"\n")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            val source=TranscriptSource(client,"t1",scope,20)
            try {
                client.connect()
                source.items.first { it.filterIsInstance<ChatItem.UserMessage>().size==1 }
                val probes=peer.probes.get()
                peer.text.set(user+"\n"+answer.take(40))
                source.state.first { it is TranscriptState.Ready && !it.complete }
                assertEquals(1,source.items.value.size)
                peer.text.set(user+"\n"+answer+"\n")
                source.items.first { it.any { row -> row is ChatItem.AssistantText } }
                assertEquals(1,source.items.value.filterIsInstance<ChatItem.UserMessage>().size)
                delay(60)
                assertEquals(probes,peer.probes.get()) // Polling follows bytes, never rebinds by clock.
                assertTrue(peer.offsets.any { it==2L })
                peer.text.set(user.replace("Synthetic prompt","Replacement prompt")+"\n")
                source.items.first { it.singleOrNull()==ChatItem.UserMessage("u","Replacement prompt") }
                assertTrue(peer.offsets.count { it==1L }>=2)
                assertFalse(peer.peer.saw("doc.set"))
            } finally { source.close(); client.close(); scope.cancel() }
        }
    }
    @Test fun noExactEvidenceNeverAutoSelectsNewestAndConfirmationRequiresPreview():Unit=runBlocking {
        withTimeout(6000) {
            val peer=TranscriptPeer(user+"\n").apply { exact=false }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            val source=TranscriptSource(client,"t1",scope,20)
            try {
                client.connect()
                source.state.first { it is TranscriptState.Unavailable && it.reason=="Ambiguous fixture" }
                val probes=peer.probes.get()
                delay(60)
                assertEquals(probes,peer.probes.get())
                assertTrue(source.items.value.isEmpty())
                val candidates=source.candidates()
                assertEquals(1,candidates.size)
                assertFailsWith<IllegalArgumentException> { source.select(candidates.single()) }
                assertEquals("Synthetic prompt",source.preview(candidates.single()).filterIsInstance<ChatItem.UserMessage>().single().text)
                source.select(candidates.single())
                val ready=source.state.first { it is TranscriptState.Ready } as TranscriptState.Ready
                assertTrue(ready.binding.confirmed)
                // A different process invalidates the manual choice and removes its rows.
                peer.process.set("t1:1:124:2")
                source.refresh()
                assertIs<TranscriptState.Unavailable>(source.state.value)
                assertTrue(source.items.value.isEmpty())
            } finally { source.close(); client.close(); scope.cancel() }
        }
    }
    private class TranscriptPeer(initial:String) {
        val peer=FakeCoreTransport()
        val text=AtomicReference(initial)
        val process=AtomicReference("t1:1:123:2")
        val offsets=ConcurrentLinkedQueue<Long>()
        val probes=AtomicInteger()
        @Volatile var exact=true
        init { peer.intercept={ r ->
            val p=r["params"].obj()
            when(FakeCoreTransport.method(r)) {
                "events.subscribe" -> {
                    peer.respond(r,"""{"seq":0,"channel":2,"terminals":[{"address":"t1","state":"live","pid":123,"cwd":"/fixture","foreground":{"pid":123,"name":"claude","argv":["claude"],"cwd":"/fixture"}}]}"""); true
                }
                "machine.exec" -> {
                    probes.incrementAndGet()
                    val evidence=buildJsonObject {
                        put("process_key",process.get()); put("reason","Ambiguous fixture")
                        if(exact) put("binding",buildJsonObject { put("session_id","fixture-session"); put("path","/fixture/session.jsonl") })
                        put("candidates",buildJsonArray { add(buildJsonObject { put("session_id","fixture-session"); put("path","/fixture/session.jsonl") }) })
                    }
                    val encoded=Base64.getEncoder().encodeToString(evidence.toString().toByteArray())
                    peer.respond(r,"""{"code":0,"stdout":"$encoded","stderr":"","truncated":false,"timed_out":false,"ms":1}"""); true
                }
                "file.read" -> {
                    val offset=p["offset"]!!.jsonPrimitive.long; val limit=p["limit"]!!.jsonPrimitive.int
                    if(limit==2000) offsets.add(offset)
                    val all=Regex("[^\\n]*\\n|[^\\n]+$").findAll(text.get()).map { it.value }.toList()
                    if(offset>all.size && offset>1) {
                        peer.send(Frame(0,0,("{\"id\":"+r["id"]+",\"error\":{\"code\":-32602,\"message\":\"offset past EOF\",\"data\":{\"kind\":\"invalid_params\"}}}").toByteArray()))
                    } else {
                        val given=all.drop((offset-1).toInt()).take(limit)
                        peer.respond(r,buildJsonObject { put("text",given.joinToString("")); put("first",offset); put("last",offset+given.size-1); put("lines",all.size) }.toString())
                    }
                    true
                }
                else -> false
            }
        } }
    }
}
