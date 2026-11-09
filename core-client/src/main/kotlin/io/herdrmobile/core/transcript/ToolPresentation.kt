package io.herdrmobile.core.transcript

import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.serialization.json.*
import java.time.Instant

internal fun recordTime(record:JsonObject):Long? = runCatching {
    Instant.parse(record.text("timestamp")).toEpochMilli()
}.getOrNull()

/** Match entire tool identifiers; tool-name substrings and user text never grant a meaning. */
internal fun toolPresentation(kind:AgentKind,name:String,input:JsonElement?):Pair<String?,String?> {
    val data=when(input) {
        is JsonPrimitive -> runCatching { HcJson.parseToJsonElement(input.content).obj() }.getOrDefault(JsonObject(emptyMap()))
        else -> input.obj()
    }
    fun target(key:String)=data[key]?.let { if(it is JsonArray) it.joinToString(" ") { part -> part.str() } else it.str() }?.takeIf { it.isNotBlank() }
    return when(kind) {
        AgentKind.CLAUDE_CODE -> when(name) {
            "Bash" -> "Ran" to target("command")
            "Read" -> "Read" to target("file_path")
            "Edit","MultiEdit" -> "Edited" to target("file_path")
            "Write" -> "Wrote" to target("file_path")
            "Grep","Glob" -> "Searched" to target("pattern")
            "WebFetch" -> "Fetched" to target("url")
            else -> null to null
        }
        AgentKind.CODEX -> when(name) {
            "exec_command","shell" -> "Ran" to (target("cmd") ?: target("command"))
            "local_shell_call" -> "Ran" to (target("cmd") ?: target("command") ?: data["action"].obj()["command"].arr().joinToString(" ") { it.str() }.ifEmpty { null })
            "apply_patch" -> {
                val patch=data.text("input").ifEmpty { data.text("patch") }.ifEmpty { if(input is JsonPrimitive) input.content else "" }
                val paths=Regex("(?m)^\\*\\*\\* (?:Add|Update|Delete) File: (.+)$").findAll(patch).map { it.groupValues[1].trimEnd('\r') }.distinct().toList()
                "Edited" to paths.firstOrNull()?.let { if(paths.size==1) it else "$it (+${paths.size-1})" }
            }
            else -> null to null
        }
        else -> null to null
    }
}
