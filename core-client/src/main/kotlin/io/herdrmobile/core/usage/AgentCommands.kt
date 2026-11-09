package io.herdrmobile.core.usage

import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.serialization.json.*

/** Static catalog data: Ovis Claude 2.1.301 and Codex 0.163.0 reference lists. */
internal object CommandCatalog {
    private val catalog = HcJson.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/io/herdrmobile/core/usage/command_builtins.json"))
            .bufferedReader().use { it.readText() },
    ).jsonObject

    private fun section(kind: AgentKind): JsonObject? = when (kind) {
        AgentKind.CLAUDE_CODE -> catalog["claude"]!!.jsonObject
        AgentKind.CODEX -> catalog["codex"]!!.jsonObject
        else -> null
    }

    fun hidden(kind: AgentKind): Set<String> = section(kind)?.get("hidden")?.jsonArray
        ?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

    fun terminalBound(kind: AgentKind): Set<String> = section(kind)?.let { section ->
        if (kind == AgentKind.CLAUDE_CODE) section["commands"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        else section["terminalBound"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
    }.orEmpty()

    fun builtins(kind: AgentKind): List<AgentCommand> {
        val terminal = terminalBound(kind)
        return section(kind)?.get("commands")?.jsonArray?.map {
            val row = it.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            AgentCommand(name, row["description"]!!.jsonPrimitive.content,
                run = if (name in terminal) CommandRun.TERMINAL else CommandRun.CHAT)
        }?.filter { it.name !in hidden(kind) }.orEmpty()
    }
}

/** Null means an unsuccessful exchange, so a store can retain its last successful catalog. */
internal fun parseAgentCommands(kind: AgentKind, text: String): List<AgentCommand>? {
    val root = HcJson.parseToJsonElement(text) as? JsonObject ?: return null
    if (root["error"] != null || root["limitsUnavailable"] != null) return null
    val rows = root["commands"] as? JsonArray ?: return null
    fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    val dynamic = rows.mapNotNull {
        val row = it as? JsonObject ?: return@mapNotNull null
        val name = row.string("name")?.takeIf { name ->
            name.isNotBlank() && name.none { it.isWhitespace() || it.isISOControl() } && !name.startsWith('/') && !name.startsWith('$')
        } ?: return@mapNotNull null
        val description = row.string("description") ?: return@mapNotNull null
        when (kind) {
            AgentKind.CLAUDE_CODE -> {
                val builtin = if ("builtin" !in row) false else
                    (row["builtin"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                        ?: return@mapNotNull null
                // Initialize distinguishes builtins from extensions, but does not identify extension subtypes.
                AgentCommand(name, description, row.string("argumentHint").orEmpty(), source = if (builtin) "builtin" else "custom")
            }
            AgentKind.CODEX -> AgentCommand(name, description, trigger = '$', source = "skill")
            else -> return@mapNotNull null
        }
    }
    return mergeAgentCommands(kind, dynamic)
}

internal fun mergeAgentCommands(kind: AgentKind, commands: List<AgentCommand>): List<AgentCommand> {
    val static = CommandCatalog.builtins(kind)
    // Claude's native descriptions/hints take priority; Codex's builtins are entirely static.
    val candidates = if (kind == AgentKind.CLAUDE_CODE) commands + static else static + commands
    val terminal = CommandCatalog.terminalBound(kind)
    val hidden = CommandCatalog.hidden(kind)
    val priority = mapOf("builtin" to 0, "custom" to 1, "skill" to 2)
    return candidates.filter { it.name !in hidden }
        .sortedBy { priority[it.source] ?: 3 }
        .distinctBy { it.name }
        .map { if (it.name in terminal) it.copy(run = CommandRun.TERMINAL) else it }
}
