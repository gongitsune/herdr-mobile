package io.herdrmobile.android.ui

import io.herdrmobile.core.workspace.AgentKind
import io.herdrmobile.core.workspace.Space
import io.herdrmobile.core.workspace.Tab

internal data class WorkspaceAgentLink(
    val terminalId: String,
    val title: String,
    val kind: AgentKind?,
    val selected: Boolean,
    val stale: Boolean,
)

internal data class WorkspaceAgentHeader(val title: String, val subtitle: String, val kind: AgentKind?)

internal fun relatedAgentLinks(tab: Tab?, terminalId: String, staleTerminalIds: Set<String> = emptySet()): List<WorkspaceAgentLink> {
    val parent = tab?.panes?.find { it.terminal.id == terminalId || it.subAgents.any { child -> child.terminal.id == terminalId } }
        ?: return emptyList()
    if (parent.subAgents.isEmpty()) return emptyList()
    // Keep the parent visible even when selected, so the switcher always identifies the current terminal.
    return (listOf(parent) + parent.subAgents).map { pane ->
        WorkspaceAgentLink(pane.terminal.id, pane.treeTitle(tab.name), pane.agent?.kind,
            selected = pane.terminal.id == terminalId,
            stale = pane.terminal.id in staleTerminalIds || pane.terminal.state == "finished")
    }
}

internal fun workspaceAgentHeader(space: Space?, tab: Tab?, terminalId: String, fallbackTitle: String): WorkspaceAgentHeader {
    val child = tab?.panes?.flatMap { it.subAgents }?.find { it.terminal.id == terminalId }
    return if (child != null) WorkspaceAgentHeader(
        child.treeTitle(tab.name),
        listOfNotNull(space?.name?.takeIf { it.isNotBlank() }, tab.displayTitle().ifBlank { tab.name }.takeIf { it.isNotBlank() })
            .joinToString(" · "),
        child.agent?.kind,
    ) else WorkspaceAgentHeader(
        tab?.displayTitle()?.takeIf { it.isNotBlank() } ?: fallbackTitle,
        space?.name.orEmpty(), tab?.openingPane()?.agent?.kind,
    )
}
