package io.herdrmobile.android.ui

import io.herdrmobile.core.workspace.Pane
import io.herdrmobile.core.workspace.Space
import io.herdrmobile.core.workspace.Tab
import io.herdrmobile.android.connection.ConsoleTerminal

internal fun Tab.openingPane(): Pane? = panes.firstOrNull { it.agent != null } ?: panes.firstOrNull()

internal fun Space.openingPane(previousTab: String? = null): Pane? =
    tabs.find { it.id == previousTab }?.openingPane()
        ?: tabs.flatMap { it.panes }.firstOrNull { it.agent != null }
        ?: tabs.firstNotNullOfOrNull { it.panes.firstOrNull() }

internal fun Tab.aggregateState(): String? = panes.mapNotNull { it.terminal.status?.state }
    .minByOrNull { when (it) { "blocked", "error" -> 0; "working" -> 1; "done" -> 2; else -> 3 } }

internal fun ConsoleTerminal.needsAttention(unread: Boolean): Boolean = !stale &&
    (terminal.status?.state in listOf("blocked", "error", "working") || (terminal.status?.state == "done" && unread))

internal fun Tab.terminalIds(): List<String> = panes.map { it.terminal.id }.distinct()

internal fun Tab.containsTerminal(id: String?): Boolean = panes.any {
    it.terminal.id == id || it.subAgents.any { child -> child.terminal.id == id }
}

internal fun Tab.displayTitle(): String {
    if (explicitName.isNotEmpty()) return name
    val pane = openingPane()
    return pane?.terminal?.let { it.title.ifBlank { it.name.orEmpty() }.ifBlank { pane.agent?.program.orEmpty() }.ifBlank { it.id } }.orEmpty()
}

internal suspend fun closeTabTerminals(ids: List<String>, kill: suspend (String) -> Unit): List<Pair<String, io.herdrmobile.core.rpc.HcError>> {
    val errors = mutableListOf<Pair<String, io.herdrmobile.core.rpc.HcError>>()
    ids.forEach { id ->
        try { kill(id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            val error = e.asHcError()
            if (error.kind !in listOf("not_found", "terminal_not_found", "not_live", "terminal_not_live")) errors += id to error
        }
    }
    return errors
}

internal fun renameChanged(original: String, value: String): Boolean = original != value.trim()
