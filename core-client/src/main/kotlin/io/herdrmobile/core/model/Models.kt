package io.herdrmobile.core.model
import kotlinx.serialization.*
import kotlinx.serialization.json.*
val HcJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
@Serializable
data class Terminal(
    val address: String = "",
    val argv: List<String> = emptyList(),
    val cols: Int = 0,
    val created_ms: Long = 0,
    val cwd: String = "",
    val die_with_parent: Boolean = false,
    val exit_code: Int? = null,
    val finished_ms: Long? = null,
    val first_message: FirstMessage? = null,
    val foreground: Foreground? = null,
    val identity: String = "",
    val keep: Boolean = false,
    val labels: Map<String, String> = emptyMap(),
    val name: String? = null,
    val notify: Boolean = false,
    val parent: String? = null,
    val paste_gap_ms: Long? = null,
    val pid: Long? = null,
    val reason: String? = null,
    val records: Map<String, Status> = emptyMap(),
    val reported: Boolean = false,
    val rm: Boolean = false,
    val rows: Int = 0,
    val screen: String = "",
    val signal: Int? = null,
    val state: String = "",
    val status: Status? = null,
    val status_ms: Long? = null,
    val stop: String? = null,
    val submit: String? = null,
    val template: String? = null,
    val title: String = "",
    val turn: Long = 0,
) {
    val id: String get() = address
    val priority: Int get() = if (state == "finished") 5 else when (status?.state) { "blocked", "error" -> 0; "working" -> 1; "done" -> 2; "idle" -> 3; else -> 4 }
    val size: InitialSize get() = InitialSize(cols, rows)
}
@Serializable
data class Status(
    val app: String = "",
    val kind: String? = null,
    val msg: String = "",
    val progress: Int? = null,
    val state: String = "",
    val title: String = "",
)
@Serializable
data class CoreInfo(
    val id: String = "",
    val name: String = "",
)
@Serializable
data class Foreground(
    val argv: List<String> = emptyList(),
    val cwd: String = "",
    val name: String = "",
    val pid: Int = 0,
)
@Serializable
data class Template(
    val command: String = "",
    val description: String = "",
    val error: String? = null,
    val name: String = "",
)
@Serializable
data class FirstMessage(
    val reason: String? = null,
    val sent: Boolean = false,
    val started: Boolean? = null,
    val turn: Long? = null,
)
@Serializable data class InitialSize(val cols: Int, val rows: Int)
@Serializable data class CloseReason(val reason: String? = null, val exit_code: Int? = null, val signal: Int? = null)
@Serializable data class Event(val seq: Long, val type: String, val terminal: String? = null, val data: JsonElement = JsonNull)
