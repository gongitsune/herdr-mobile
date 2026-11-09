package io.herdrmobile.core.rpc

import io.herdrmobile.core.*
import io.herdrmobile.core.model.*
import io.herdrmobile.core.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class HcError(val kind: String, override val message: String, val code: Int? = null, val data: JsonElement? = null) : Exception(message)

class CoreConnection(private val transport: ByteTransport, private val scope: CoroutineScope) : java.io.Closeable {
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val streams = ConcurrentHashMap<Long, Channel<Frame>>()
    private val writer = Mutex()
    private var reader: Job? = null
    private val ended = java.util.concurrent.atomic.AtomicBoolean()
    internal val isClosed: Boolean get() = ended.get()
    @Volatile private var endError: Exception? = null
    val completion = CompletableDeferred<Unit>()
    lateinit var hello: JsonObject
        private set

    suspend fun start(): JsonObject {
        try {
            hello = withContext(Dispatchers.IO) {
                val request = request(0, "hello", buildJsonObject { put("generation", 1); put("framed", true) })
                transport.output.write((request.toString() + "\n").toByteArray())
                transport.output.flush()
                val line = ByteArrayOutputStream()
                while (true) {
                    val b = transport.input.read()
                    if (b < 0) throw EOFException("No hello reply")
                    if (b == 10) break
                    require(line.size() < FrameCodec.MAX_PAYLOAD)
                    line.write(b)
                }
                val reply = HcJson.parseToJsonElement(line.toString("UTF-8")).jsonObject
                require(reply["id"]?.jsonPrimitive?.long == 0L)
                result(reply).jsonObject
            }
            val features = hello["features"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            if (hello["generation"]?.jsonPrimitive?.int != 1 || hello["framed"]?.jsonPrimitive?.boolean != true ||
                !features.containsAll(setOf("framed", "attach", "events", "status"))) {
                throw HcError("unsupported", "herdr-core を更新してください")
            }
            reader = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val frame = FrameCodec.decode(transport.input) ?: throw EOFException("Core disconnected")
                        if (frame.channel == 0L && frame.kind == Frame.DATA) {
                            val message = HcJson.parseToJsonElement(frame.payload.decodeToString()).jsonObject
                            val id = message["id"]?.jsonPrimitive?.longOrNull ?: continue
                            val deferred = pending.remove(id) ?: continue
                            try {
                                val value = result(message)
                                // Install before waking the caller: stream frames may immediately follow.
                                (value as? JsonObject)?.get("channel")?.jsonPrimitive?.longOrNull?.let { channel(it) }
                                deferred.complete(value)
                            } catch (e: Exception) { deferred.completeExceptionally(e) }
                        } else {
                            val queue = streams[frame.channel] ?: continue
                            queue.trySend(frame) // A concurrently released stream may already be closed; ignore it.
                            if (frame.kind == Frame.CLOSE) queue.close()
                        }
                    }
                } catch (e: Exception) { shutdown(e) }
            }
            return hello
        } catch (e: Exception) { shutdown(e); throw e }
    }

    // Do not cap by frame count: tiny frames must not disconnect a slow UI.
    // Attach bytes are bounded by CREDIT; event backlog/replacement is managed by core's missed.
    fun channel(id: Long): Channel<Frame> {
        val queue = streams.computeIfAbsent(id) { Channel(Channel.UNLIMITED) }
        // An attach reply may race disconnect before its caller installs the stream consumer.
        // Never create a live orphan channel after shutdown has cleared the registry.
        if (ended.get()) {
            queue.close(endError ?: HcError("io", "Connection closed"))
            streams.remove(id, queue)
        }
        return queue
    }
    fun release(id: Long) { streams.remove(id)?.close() }
    suspend fun call(method: String, params: JsonObject): JsonElement {
        check(!ended.get()) { "Connection closed" }
        val id = ids.getAndIncrement()
        val reply = CompletableDeferred<JsonElement>()
        pending[id] = reply
        try {
            if (ended.get()) throw EOFException("Connection closed")
            send(Frame(0, Frame.DATA, request(id, method, params).toString().toByteArray()))
            return reply.await()
        } finally { pending.remove(id) }
    }
    suspend fun send(frame: Frame) = withContext(Dispatchers.IO) {
        writer.withLock {
            check(!ended.get()) { "Connection closed" }
            transport.output.write(FrameCodec.encode(frame))
            transport.output.flush()
        }
    }
    suspend fun credit(channel: Long, bytes: Int) = send(Frame(channel, Frame.CREDIT, FrameCodec.u32(bytes)))
    private fun request(id: Long, method: String, params: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
    }
    private fun result(message: JsonObject): JsonElement {
        message["error"]?.jsonObject?.let {
            throw HcError((it["data"] as? JsonObject)?.get("kind")?.jsonPrimitive?.content ?: "rpc",
                it["message"]?.jsonPrimitive?.content ?: "RPC error", it["code"]?.jsonPrimitive?.int, it["data"])
        }
        return message["result"] ?: JsonNull
    }
    private fun shutdown(error: Exception) {
        if (!ended.compareAndSet(false, true)) return
        endError = error
        runCatching { transport.close() }
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        streams.values.forEach { it.close(error) }
        streams.clear()
        completion.completeExceptionally(error)
    }
    internal fun fail(error: Exception) { shutdown(error) }
    override fun close() {
        shutdown(HcError("io", "Connection closed"))
        reader?.cancel()
    }
}
