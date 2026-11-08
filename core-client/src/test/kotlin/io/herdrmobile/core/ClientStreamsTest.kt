package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.*
import kotlin.test.*

class ClientStreamsTest {
    private class Peer : ByteTransport {
        private val requests = PipedInputStream(1024*1024)
        override val output = PipedOutputStream(requests)
        override val input = PipedInputStream(1024*1024)
        private val replies = PipedOutputStream(input)
        fun hello(automatic: Boolean) {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = requests.read()
                check(b >= 0)
                if (b == 10) break
                line.write(b)
            }
            val features = mutableListOf("framed","attach","events","status")
            if (automatic) features += "automatic_input"
            val result = buildJsonObject {
                put("generation",1); put("framed",true); put("core",buildJsonObject { put("id","test"); put("name","test") })
                put("features",JsonArray(features.map(::JsonPrimitive)))
            }
            replies.write(("{\"id\":0,\"result\":$result}\n").toByteArray()); replies.flush()
        }
        fun request(): JsonObject = HcJson.parseToJsonElement(frame().payload.decodeToString()).jsonObject
        fun frame(): Frame = FrameCodec.decode(requests)!!
        fun reply(request: JsonObject, result: String) {
            send(Frame(0,0,("{\"id\":${request["id"]},\"result\":$result}").toByteArray()))
        }
        fun send(frame: Frame) { replies.write(FrameCodec.encode(frame)); replies.flush() }
        override fun close() { input.close(); output.close(); requests.close(); replies.close() }
    }
    @Test fun automaticInputRequiresBothNegotiationsAndCreditFollowsConsumption() = runBlocking {
        for ((helloAutomatic,attachAutomatic) in listOf(true to true,true to false,false to true)) {
            withTimeout(10000) {
                val peer = Peer()
                val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
                val consumed = CompletableDeferred<Unit>()
                val credited = CompletableDeferred<Unit>()
                val server = scope.async {
                    peer.hello(helloAutomatic)
                    val request = peer.request()
                    assertEquals("terminal.attach",request["method"]!!.jsonPrimitive.content)
                    peer.reply(request,"""{"channel":1,"automatic_input":$attachAutomatic}""")
                    peer.send(Frame(1,Frame.DATA,"paint".toByteArray()))
                    val input = peer.frame()
                    assertEquals(if (helloAutomatic && attachAutomatic) Frame.AUTOMATIC_INPUT else Frame.DATA,input.kind)
                    assertContentEquals("reply".toByteArray(),input.payload)
                    // The collector releases this only after it has consumed the bytes.
                    val credit = peer.frame()
                    assertTrue(consumed.isCompleted)
                    assertEquals(Frame.CREDIT,credit.kind)
                    assertContentEquals(FrameCodec.u32(5),credit.payload)
                    credited.complete(Unit)
                    val resize = peer.frame()
                    assertEquals(Frame.RESIZE,resize.kind)
                    assertContentEquals(FrameCodec.size(90,30),resize.payload)
                    assertEquals(Frame.FOCUS,peer.frame().kind)
                    assertEquals(Frame.CLOSE,peer.frame().kind)
                    peer.send(Frame(1,Frame.CLOSE))
                }
                val client = HcClient(TransportFactory { peer },scope)
                try {
                    client.connect()
                    val session = client.attach("t-test",80,24)
                    val output = launch(start = CoroutineStart.LAZY) {
                        session.output.collect {
                            assertEquals("paint",it.decodeToString())
                            consumed.complete(Unit)
                        }
                    }
                    session.writeAutomatic("reply".toByteArray())
                    output.start()
                    consumed.await()
                    credited.await()
                    session.resize(90,30)
                    session.focus(true)
                    session.detach()
                    assertNull(session.closed.await().reason)
                    output.join()
                    server.await()
                } finally { client.close(); scope.cancel() }
            }
        }
    }
    @Test fun eventLinesCanSpanFramesAndMissedReplacesSnapshot() = runBlocking {
        withTimeout(10000) {
            val peer = Peer()
            val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val publish = CompletableDeferred<Unit>()
            val server = scope.async {
                peer.hello(false)
                val subscribe = peer.request()
                assertEquals("events.subscribe",subscribe["method"]!!.jsonPrimitive.content)
                peer.reply(subscribe,"""{"channel":2,"seq":10,"terminals":[{"address":"t1","state":"live"}]}""")
                publish.await()
                val messages = """
{"method":"event","params":{"seq":11,"type":"title","terminal":"t1","data":{"title":"old"}}}
{"method":"missed","params":{"seq":20,"terminals":[{"address":"t2","state":"live","title":"fresh"}]}}
{"method":"event","params":{"seq":19,"type":"removed","terminal":"t2","data":{}}}
{"method":"event","params":{"seq":21,"type":"title","terminal":"t2","data":{"title":"new"}}}
""".trimStart().toByteArray()
                val parts = listOf(messages.copyOfRange(0,7),messages.copyOfRange(7,messages.size))
                for (part in parts) {
                    peer.send(Frame(2,Frame.DATA,part))
                    val credit = peer.frame()
                    assertEquals(Frame.CREDIT,credit.kind)
                    assertContentEquals(FrameCodec.u32(part.size),credit.payload)
                }
            }
            val client = HcClient(TransportFactory { peer },scope)
            try {
                client.connect()
                val store = TerminalStore(client,scope)
                store.terminals.first { it.singleOrNull()?.id == "t1" }
                publish.complete(Unit)
                val updated = try {
                    withTimeout(3000) { store.terminals.first { it.singleOrNull()?.title == "new" } }
                } catch (e: Exception) {
                    throw AssertionError("state=${client.state.value}, terminals=${store.terminals.value}, server=${server.isCompleted}",e)
                }
                assertEquals("t2",updated.single().id)
                server.await()
            } finally { client.close(); scope.cancel() }
        }
    }
    @Test fun slowSingleConsumerDoesNotDisconnectAfter512SmallFrames(): Unit = runBlocking {
        withTimeout(10000) {
            val peer = Peer()
            val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val server = scope.async {
                peer.hello(false)
                peer.reply(peer.request(),"""{"channel":3,"automatic_input":false}""")
                repeat(1024) { peer.send(Frame(3,Frame.DATA,byteArrayOf(65))) }
                peer.send(Frame(3,Frame.CLOSE))
                peer.reply(peer.request(),"""{"alive":true}""")
                repeat(1024) {
                    val credit=peer.frame()
                    assertEquals(Frame.CREDIT,credit.kind)
                    assertContentEquals(FrameCodec.u32(1),credit.payload)
                }
            }
            val client = HcClient(TransportFactory { peer },scope)
            try {
                client.connect()
                val session=client.attach("t1",80,24)
                session.closed.await() // No collector yet: more than 512 tiny frames must be retained.
                assertTrue(client.call("ping",buildJsonObject {}) .jsonObject["alive"]!!.jsonPrimitive.boolean)
                assertEquals(1024,session.output.toList().size)
                assertFailsWith<IllegalStateException> { session.output.toList() }
                server.await()
            } finally { client.close(); scope.cancel() }
        }
    }

}
