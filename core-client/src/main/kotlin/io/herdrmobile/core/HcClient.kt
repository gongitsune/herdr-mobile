package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.rpc.*
import io.herdrmobile.core.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long) : ConnectionState
    data class Connected(val core: CoreInfo, val features: Set<String>) : ConnectionState
    data class Failed(val error: HcError) : ConnectionState
}
/**
 * Owns one connection lifecycle. Failed RPCs and terminal input are never replayed.
 * connect/reconnectNow await the first attempt; retryable failures continue in the supplied scope.
 */
class HcClient internal constructor(
    private val factory: TransportFactory,
    private val scope: CoroutineScope,
    autoReconnect: Boolean,
    private val retryDelay: (attempt: Int) -> Long,
) {
    constructor(factory: TransportFactory, scope: CoroutineScope, autoReconnect: Boolean = true) :
        this(factory, scope, autoReconnect, ::reconnectDelayMs)

    private val lifecycle = Any()
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    private val live = MutableStateFlow<CoreConnection?>(null)
    // Identity, not Connected's core/features equality: a fast reconnect can conflate state changes.
    internal val connections: StateFlow<CoreConnection?> = live.asStateFlow()
    internal val connection: CoreConnection? get() = live.value
    private var opening: CoreConnection? = null
    private var generation = 0L
    private var wanted = false
    private var enabled = autoReconnect
    private var worker: Job? = null
    private var lastError: HcError? = null

    /** Disabling cancels pending retries, while preserving an established connection. */
    var autoReconnect: Boolean
        get() = synchronized(lifecycle) { enabled }
        set(value) {
            var stop: Job? = null
            var closeOpening: CoreConnection? = null
            var start: Run? = null
            synchronized(lifecycle) {
                if (enabled == value) return
                enabled = value
                if (!value && mutableState.value is ConnectionState.Reconnecting) {
                    generation++
                    stop = worker
                    worker = null
                    closeOpening = opening
                    opening = null
                    mutableState.value = ConnectionState.Failed(checkNotNull(lastError))
                } else if (value && wanted && mutableState.value is ConnectionState.Failed &&
                    lastError?.retryable == true) {
                    start = newRunLocked(waitFirst = true)
                }
            }
            stop?.cancel()
            closeOpening?.close()
            start?.job?.start()
        }

    suspend fun connect() {
        val run: Run
        val previous: Job?
        val old: List<CoreConnection>
        synchronized(lifecycle) {
            previous = worker
            old = listOfNotNull(live.value, opening).distinct()
            live.value = null
            opening = null
            wanted = true
            lastError = null
            run = newRunLocked(waitFirst = false)
            mutableState.value = ConnectionState.Connecting
        }
        previous?.cancel()
        old.forEach { it.close() }
        run.job.start()
        try { run.first.await() }
        catch (e: CancellationException) {
            stop(run.generation)
            throw e
        }
    }

    /** Overrides the retry delay and retries even after a permanent failure or when auto is off. */
    suspend fun reconnectNow() = connect()

    fun close() { stop(null) }

    private fun stop(expected: Long?) {
        val job: Job?
        val old: List<CoreConnection>
        synchronized(lifecycle) {
            if (expected != null && expected != generation) return
            generation++
            wanted = false
            job = worker
            worker = null
            old = listOfNotNull(live.value, opening).distinct()
            live.value = null
            opening = null
            mutableState.value = ConnectionState.Disconnected
        }
        job?.cancel()
        old.forEach { it.close() }
    }

    private data class Run(val generation: Long, val job: Job, val first: CompletableDeferred<Unit>)

    // Called under lifecycle. Lazy start lets the caller publish state before running user code.
    private fun newRunLocked(waitFirst: Boolean): Run {
        val token = ++generation
        val first = CompletableDeferred<Unit>()
        val job = scope.launch(start = CoroutineStart.LAZY) { runConnections(token, first, waitFirst) }
        worker = job
        job.invokeOnCompletion { cause ->
            first.completeExceptionally(cause ?: CancellationException("Connection attempt ended"))
            var old: List<CoreConnection> = emptyList()
            synchronized(lifecycle) {
                if (token == generation && worker === job) {
                    worker = null
                    if (cause is CancellationException) {
                        wanted = false
                        old = listOfNotNull(live.value, opening).distinct()
                        live.value = null
                        opening = null
                        mutableState.value = ConnectionState.Disconnected
                    }
                }
            }
            old.forEach { it.close() }
        }
        return Run(token, job, first)
    }

    private suspend fun runConnections(token: Long, first: CompletableDeferred<Unit>, waitFirst: Boolean) {
        var attempt = if (waitFirst) 1 else 0
        while (currentCoroutineContext().isActive) {
            if (attempt > 0) {
                val wait = retryDelay(attempt).coerceIn(0, 30_000)
                synchronized(lifecycle) {
                    if (token != generation || !wanted) return
                    if (!enabled) {
                        mutableState.value = ConnectionState.Failed(checkNotNull(lastError))
                        return
                    }
                    mutableState.value = ConnectionState.Reconnecting(attempt, wait)
                }
                delay(wait)
            }
            var raw: ByteTransport? = null
            var current: CoreConnection? = null
            try {
                synchronized(lifecycle) {
                    if (token != generation || !wanted) return
                }
                withContext(Dispatchers.IO) { factory.open().also { raw = it } }
                current = CoreConnection(checkNotNull(raw), scope)
                synchronized(lifecycle) {
                    if (token != generation || !wanted) throw CancellationException("Connection replaced")
                    opening = current
                }
                val hello = current.start()
                val info = HcJson.decodeFromJsonElement<CoreInfo>(hello["core"]!!)
                val features = hello["features"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                synchronized(lifecycle) {
                    if (token != generation || !wanted) throw CancellationException("Connection replaced")
                    opening = null
                    live.value = current
                    lastError = null
                    mutableState.value = ConnectionState.Connected(info, features)
                }
                attempt = 0
                first.complete(Unit)
                current.completion.await()
                throw java.io.EOFException("Core disconnected")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = connectionError(e)
                val retry = synchronized(lifecycle) {
                    if (token != generation || !wanted) return
                    live.value = null
                    opening = null
                    lastError = failure
                    val again = enabled && failure.retryable
                    if (!again) mutableState.value = ConnectionState.Failed(failure)
                    again
                }
                first.completeExceptionally(failure)
                if (!retry) return
                attempt++
            } finally {
                if (current != null) current.close() else runCatching { raw?.close() }
            }
        }
    }

    suspend fun call(method: String, params: JsonObject): JsonElement =
        (connection ?: throw HcError("io", "Not connected")).call(method, params)
    suspend fun list(all: Boolean = false): List<Terminal> =
        HcJson.decodeFromJsonElement(call("terminal.list", buildJsonObject { put("all", all) }).jsonObject["terminals"]!!)
    suspend fun get(id: String): Terminal = HcJson.decodeFromJsonElement(call("terminal.get", terminalParams(id)))
    suspend fun run(argv: List<String>? = null, line: String? = null, cwd: String? = null,
                    name: String? = null, keep: Boolean = false, labels: Map<String, String> = emptyMap()): Terminal =
        HcJson.decodeFromJsonElement(call("terminal.run", buildJsonObject {
            argv?.let { put("argv", JsonArray(it.map(::JsonPrimitive))) }
            line?.let { put("line", it) }; cwd?.let { put("cwd", it) }; name?.let { put("name", it) }
            put("keep", keep); put("labels", JsonObject(labels.mapValues { JsonPrimitive(it.value) }))
        }))
    suspend fun templates(): List<Template> = HcJson.decodeFromJsonElement(call("templates.list", buildJsonObject {}).jsonObject["templates"]!!)
    suspend fun send(id: String, text: String, force: Boolean = false) { call("terminal.send", terminalParams(id) { put("text", text); put("force", force) }) }
    suspend fun keys(id: String, keys: List<String>) { call("terminal.keys", terminalParams(id) { put("keys", JsonArray(keys.map(::JsonPrimitive))) }) }
    suspend fun interrupt(id: String) { call("terminal.interrupt", terminalParams(id)) }
    suspend fun kill(id: String) { call("terminal.kill", terminalParams(id)) }
    suspend fun rm(id: String) { call("terminal.rm", terminalParams(id)) }
    suspend fun read(id: String, lines: Int = 200): String = call("terminal.read", terminalParams(id) { put("lines", lines) }).jsonObject["text"]!!.jsonPrimitive.content
    suspend fun attach(id: String, cols: Int, rows: Int, observe: Boolean = false, history: Int? = null): AttachSession {
        FrameCodec.size(cols, rows)
        val conn = requireNotNull(connection)
        val reply = conn.call("terminal.attach", terminalParams(id) {
            put("cols", cols); put("rows", rows); put("observe", observe); history?.let { require(it >= 0); put("history", it) }
        }).jsonObject
        val automatic = (state.value as? ConnectionState.Connected)?.features?.contains("automatic_input") == true &&
            reply["automatic_input"]?.jsonPrimitive?.boolean == true
        return StreamAttach(conn, reply["channel"]!!.jsonPrimitive.long, automatic, observe, scope)
    }
}
internal fun terminalParams(id: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("terminal", id); extra() }

interface AttachSession {
    /** Cold flow with a single lifetime collection. A second collection throws IllegalStateException.
     * CREDIT is returned after downstream consumes each emitted chunk.
     */
    val output: Flow<ByteArray>
    /** A lost connection completes normally with CloseReason("disconnected"); reattach is explicit. */
    val closed: Deferred<CloseReason>
    suspend fun write(bytes: ByteArray)
    suspend fun writeAutomatic(bytes: ByteArray)
    suspend fun resize(cols: Int, rows: Int)
    suspend fun focus(gained: Boolean)
    suspend fun detach()
}
private class StreamAttach(
    private val conn: CoreConnection, private val id: Long, private val automatic: Boolean,
    private val observe: Boolean, scope: CoroutineScope
) : AttachSession {
    private val done = CompletableDeferred<CloseReason>()
    override val closed: Deferred<CloseReason> = done
    // CREDIT bounds queued attach bytes; a frame-count limit would penalize small chunks.
    private val data = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val collected = java.util.concurrent.atomic.AtomicBoolean()
    init {
        scope.launch {
            try {
                for (frame in conn.channel(id)) when (frame.kind) {
                    Frame.DATA -> data.send(frame.payload)
                    Frame.CLOSE -> {
                        done.complete(if (frame.payload.isEmpty()) CloseReason() else HcJson.decodeFromString<CloseReason>(frame.payload.decodeToString()))
                    }
                }
                if (!done.isCompleted) done.complete(CloseReason("disconnected"))
                data.close()
            } catch (e: Exception) { done.complete(CloseReason("disconnected")); data.close(e) }
            finally { conn.release(id) }
        }.invokeOnCompletion { cause ->
            if (cause != null) {
                done.complete(CloseReason("disconnected"))
                data.close(cause)
                conn.release(id)
            }
        }
    }
    override val output: Flow<ByteArray> = flow {
        check(collected.compareAndSet(false, true)) { "AttachSession.output can only be collected once" }
        for (bytes in data) {
            try { emit(bytes) }
            finally { withContext(NonCancellable) { conn.credit(id, bytes.size) } }
        }
    }
    override suspend fun write(bytes: ByteArray) { check(!observe); sendBytes(Frame.DATA, bytes) }
    override suspend fun writeAutomatic(bytes: ByteArray) { check(!observe); sendBytes(if (automatic) Frame.AUTOMATIC_INPUT else Frame.DATA, bytes) }
    private suspend fun sendBytes(kind: Int, bytes: ByteArray) {
        check(!done.isCompleted)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + FrameCodec.MAX_PAYLOAD, bytes.size)
            conn.send(Frame(id, kind, bytes.copyOfRange(offset, end))); offset = end
        }
    }
    override suspend fun resize(cols: Int, rows: Int) { check(!observe); conn.send(Frame(id, Frame.RESIZE, FrameCodec.size(cols, rows))) }
    override suspend fun focus(gained: Boolean) { if (!observe) conn.send(Frame(id, Frame.FOCUS, byteArrayOf(if (gained) 1 else 0))) }
    override suspend fun detach() { conn.send(Frame(id, Frame.CLOSE)) }
}
