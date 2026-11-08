package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.rpc.*
import io.herdrmobile.core.wire.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.*
import kotlin.test.*

class CoreConnectionTest {
    private class PipeTransport : ByteTransport {
        val serverInput = PipedInputStream(1024 * 1024)
        override val output = PipedOutputStream(serverInput)
        override val input = PipedInputStream(1024 * 1024)
        val serverOutput = PipedOutputStream(input)
        override fun close() { input.close(); output.close(); serverInput.close(); serverOutput.close() }
    }
    @Test fun multiplexOutOfOrderImmediateStreamAndError() = runBlocking {
        withTimeout(10000) {
            val transport = PipeTransport()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val server = scope.async {
                val helloLine = transport.serverInput.bufferedReader().readLine()
                val hello = HcJson.parseToJsonElement(helloLine).jsonObject
                assertEquals("hello", hello["method"]!!.jsonPrimitive.content)
                // Both modes share the same write, exercising the exact transition.
                transport.serverOutput.write(("{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"generation\":1,\"framed\":true,\"features\":[\"framed\",\"attach\",\"events\",\"status\"]}}\n").toByteArray())
                transport.serverOutput.flush()
                val a = HcJson.parseToJsonElement(FrameCodec.decode(transport.serverInput)!!.payload.decodeToString()).jsonObject
                val b = HcJson.parseToJsonElement(FrameCodec.decode(transport.serverInput)!!.payload.decodeToString()).jsonObject
                for (request in listOf(b,a)) {
                    val method = request["method"]!!.jsonPrimitive.content
                    val reply = buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", request["id"]!!)
                        put("result", if (method == "attach") buildJsonObject { put("channel", 7) } else JsonPrimitive(method))
                    }
                    transport.serverOutput.write(FrameCodec.encode(Frame(0,Frame.DATA,reply.toString().toByteArray())))
                    if (method == "attach") {
                        transport.serverOutput.write(FrameCodec.encode(Frame(7,Frame.DATA,"paint".toByteArray())))
                        transport.serverOutput.write(FrameCodec.encode(Frame(7,Frame.CLOSE,"{\"reason\":\"exited\",\"exit_code\":0}".toByteArray())))
                    }
                    transport.serverOutput.flush()
                }
                val credit = FrameCodec.decode(transport.serverInput)!!
                assertEquals(Frame.CREDIT, credit.kind)
                assertContentEquals(FrameCodec.u32(5), credit.payload)
                val bad = HcJson.parseToJsonElement(FrameCodec.decode(transport.serverInput)!!.payload.decodeToString()).jsonObject
                val error = """{"id":${bad["id"]},"error":{"code":-32000,"message":"missing","data":{"kind":"not_found"}}}"""
                transport.serverOutput.write(FrameCodec.encode(Frame(0,0,error.toByteArray())))
                transport.serverOutput.flush()
            }
            val conn = CoreConnection(transport,scope)
            try {
                conn.start()
                val a = async { conn.call("attach", buildJsonObject {}) }
                val b = async { conn.call("other", buildJsonObject {}) }
                assertEquals("other", b.await().jsonPrimitive.content)
                assertEquals(7, a.await().jsonObject["channel"]!!.jsonPrimitive.int)
                val frames = conn.channel(7)
                assertEquals("paint", frames.receive().payload.decodeToString())
                assertEquals(Frame.CLOSE, frames.receive().kind)
                conn.credit(7,5)
                val e = assertFailsWith<HcError> { conn.call("bad", buildJsonObject {}) }
                assertEquals("not_found",e.kind)
                server.await()
            } finally { conn.close(); scope.cancel() }
        }
    }
    @Test fun unknownFieldsAndPriority() {
        val t = HcJson.decodeFromString<Terminal>("""{"address":"t1","future":true,"status":{"state":"blocked","future":3}}""")
        assertEquals("t1",t.id)
        assertEquals(0,t.priority)
        assertEquals(5,t.copy(state="finished").priority)
    }
    @Test fun disconnectFailsPending() = runBlocking {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport = PipeTransport()
        val server = scope.async {
            transport.serverInput.bufferedReader().readLine()
            transport.serverOutput.write("""{"id":0,"result":{"generation":1,"framed":true,"features":["framed","attach","events","status"]}}
""".toByteArray())
            transport.serverOutput.flush()
            FrameCodec.decode(transport.serverInput)
            transport.serverOutput.close()
        }
        val conn = CoreConnection(transport,scope)
        try {
            withTimeout(5000) {
                conn.start()
                assertFailsWith<EOFException> { conn.call("wait",buildJsonObject {}) }
                server.await()
            }
        } finally { conn.close(); scope.cancel() }
    }
}
