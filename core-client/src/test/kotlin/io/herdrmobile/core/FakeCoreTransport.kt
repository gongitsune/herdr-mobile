package io.herdrmobile.core

import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.wire.*
import kotlinx.serialization.json.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** In-memory peer; no socket, SSH, external process or background server coroutine is needed. */
internal class FakeCoreTransport(
    val terminalId: String = "t1",
    val snapshotSeq: Long = 0,
    private val replyToHello: Boolean = true,
) : ByteTransport {
    private val inbound = LinkedBlockingQueue<ByteArray>()
    val closed = AtomicBoolean()
    val requests = ConcurrentLinkedQueue<JsonObject>()
    var intercept: ((JsonObject) -> Boolean)? = null
    private var framed = false
    private var buffered = byteArrayOf()
    override val input = object : InputStream() {
        private var bytes = byteArrayOf()
        private var offset = 0
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one,0,1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (offset == bytes.size) {
                if (closed.get() && inbound.isEmpty()) return -1
                bytes = inbound.take()
                offset = 0
                if (bytes.isEmpty()) return -1
            }
            val n = minOf(len, bytes.size-offset)
            bytes.copyInto(b,off,offset,offset+n)
            offset += n
            return n
        }
    }
    override val output = object : OutputStream() {
        override fun write(b: Int) { write(byteArrayOf(b.toByte())) }
        @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get()) throw IOException("Injected transport disconnect")
            buffered += b.copyOfRange(off,off+len)
            if (!framed) {
                val end = buffered.indexOf(10)
                if (end < 0) return
                val request = HcJson.parseToJsonElement(buffered.copyOfRange(0,end).decodeToString()).jsonObject
                requests.add(request)
                buffered = buffered.copyOfRange(end+1,buffered.size)
                framed = true
                if (replyToHello) inbound.add(("{\"id\":0,\"result\":{\"generation\":1,\"framed\":true," +
                    "\"core\":{\"id\":\"same-core\",\"name\":\"test\"},\"features\":[\"framed\",\"attach\",\"events\",\"status\"]}}\n").toByteArray())
            }
            while (buffered.size >= 9) {
                val length = ByteBuffer.wrap(buffered,5,4).order(ByteOrder.LITTLE_ENDIAN).int
                if (buffered.size < 9+length) return
                val frame = FrameCodec.decode(ByteArrayInputStream(buffered.copyOf(9+length)))!!
                buffered = buffered.copyOfRange(9+length,buffered.size)
                if (frame.channel == 0L) {
                    val request=HcJson.parseToJsonElement(frame.payload.decodeToString()).jsonObject
                    requests.add(request)
                    if (intercept?.invoke(request) != true) respond(request,when(method(request)) {
                        "events.subscribe" -> """{"channel":2,"seq":$snapshotSeq,"terminals":[{"address":"$terminalId","state":"live","pid":11}]}"""
                        "terminal.attach" -> """{"channel":1,"automatic_input":false}"""
                        else -> """{"ok":true}"""
                    })
                }
            }
        }
    }
    fun respond(request: JsonObject, result: String) {
        send(Frame(0,Frame.DATA,("{\"id\":${request["id"]},\"result\":$result}").toByteArray()))
    }
    fun send(frame: Frame) { if (!closed.get()) inbound.add(FrameCodec.encode(frame)) }
    fun titleEvent(seq: Long, title: String) {
        val message=buildJsonObject {
            put("method","event")
            put("params",buildJsonObject {
                put("seq",seq); put("type","title"); put("terminal",terminalId)
                put("data",buildJsonObject { put("title",title) })
            })
        }
        send(Frame(2,Frame.DATA,(message.toString()+"\n").toByteArray()))
    }
    fun saw(method: String): Boolean = requests.any { method(it) == method }
    override fun close() {
        if (closed.compareAndSet(false,true)) inbound.offer(byteArrayOf())
    }
    companion object {
        fun method(request: JsonObject): String = request["method"]!!.jsonPrimitive.content
    }
}
