package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.rpc.CoreConnection
import io.herdrmobile.core.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

class TerminalStore(private val client: HcClient, scope: CoroutineScope) {
    private val mutable = MutableStateFlow<List<Terminal>>(emptyList())
    val terminals: StateFlow<List<Terminal>> = mutable.asStateFlow()
    private val stale = MutableStateFlow<List<Terminal>>(emptyList())
    /** Last known records while disconnected or awaiting a new snapshot; cleared after snapshot. */
    val staleTerminals: StateFlow<List<Terminal>> = stale.asStateFlow()
    internal val documentEpoch = MutableStateFlow(0L)
    internal val snapshotConnection = MutableStateFlow<CoreConnection?>(null)
    init {
        scope.launch {
            client.connections.collectLatest { conn ->
                snapshotConnection.value = null
                if (mutable.value.isNotEmpty()) stale.value = mutable.value
                mutable.value = emptyList()
                if (conn == null) return@collectLatest
                var channel: Long? = null
                try {
                    val snapshot = conn.call("events.subscribe", buildJsonObject {}).jsonObject
                    var seq = snapshot["seq"]!!.jsonPrimitive.long
                    replace(snapshot)
                    snapshotConnection.value = conn
                    val streamId = snapshot["channel"]!!.jsonPrimitive.long
                    channel = streamId
                    val line = ByteArrayOutputStream()
                    for (frame in conn.channel(streamId)) {
                        if (frame.kind == Frame.CLOSE) throw java.io.EOFException("Event subscription closed")
                        if (frame.kind != Frame.DATA) continue
                        for (byte in frame.payload) {
                            if (byte.toInt() == 10) {
                                if (line.size() > 0) {
                                    val message = HcJson.parseToJsonElement(line.toString("UTF-8")).jsonObject
                                    val params = message["params"]!!.jsonObject
                                    val next = params["seq"]!!.jsonPrimitive.long
                                    when (message["method"]!!.jsonPrimitive.content) {
                                        "missed" -> { replace(params); seq = next }
                                        "event" -> if (next > seq) {
                                            val event=HcJson.decodeFromJsonElement<Event>(params)
                                            apply(event)
                                            if (event.type == "doc" && (event.data as? JsonObject)?.get("name")?.jsonPrimitive?.content == "herdr-app")
                                                documentEpoch.value++
                                            seq = next
                                        }
                                    }
                                }
                                line.reset()
                            } else {
                                check(line.size() < FrameCodec.MAX_PAYLOAD) { "Event line too large" }
                                line.write(byte.toInt())
                            }
                        }
                        conn.credit(streamId, frame.payload.size)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { conn.fail(e) }
                finally {
                    channel?.let {
                        withContext(NonCancellable) { runCatching { conn.send(Frame(it, Frame.CLOSE)) } }
                        conn.release(it)
                    }
                }
            }
        }
    }
    private fun replace(snapshot: JsonObject) {
        publish(HcJson.decodeFromJsonElement(snapshot["terminals"]!!))
        stale.value = emptyList()
        documentEpoch.value++
    }
    private fun publish(values: List<Terminal>) { mutable.value = values.sortedWith(compareBy<Terminal> { it.priority }.thenBy { it.id }) }
    internal fun apply(event: Event) {
        val id = event.terminal ?: return
        val records = mutable.value.associateBy { it.id }.toMutableMap()
        val data = event.data
        if (event.type == "removed") records.remove(id)
        else if (event.type in setOf("created", "restarted")) records[id] = HcJson.decodeFromJsonElement(data)
        else if (event.type == "labels" && (data as? JsonObject)?.containsKey("record") == true)
            records[id] = HcJson.decodeFromJsonElement(data.jsonObject["record"]!!)
        else records[id]?.let { old ->
            val record = HcJson.encodeToJsonElement(old).jsonObject.toMutableMap()
            when (event.type) {
                "foreground" -> record["foreground"] = data
                "lost", "finished" -> {
                    record.putAll(data.jsonObject)
                    record["foreground"] = JsonNull
                    record["pid"] = JsonNull
                    if (event.type == "finished") record["state"] = JsonPrimitive("finished")
                }
                "turn" -> record["turn"] = data.jsonObject["turn"]!!
                "keep", "status", "title", "labels", "size" -> record.putAll(data.jsonObject)
            }
            records[id] = HcJson.decodeFromJsonElement(JsonObject(record))
        }
        publish(records.values.toList())
    }
}
