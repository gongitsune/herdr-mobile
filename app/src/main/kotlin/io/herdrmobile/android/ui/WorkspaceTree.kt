package io.herdrmobile.android.ui

import io.herdrmobile.core.workspace.AgentKind
import io.herdrmobile.core.workspace.Pane
import io.herdrmobile.core.workspace.Space

internal enum class WorkspaceTreeKind { TAB, PANE, SUB_AGENT, MORE }

internal data class WorkspaceTreeRow(
    val kind: WorkspaceTreeKind,
    val tabId: String,
    val depth: Int,
    val title: String = "",
    val terminalId: String? = null,
    val agentKind: AgentKind? = null,
    val state: String? = null,
    val unread: Boolean = false,
    val stale: Boolean = false,
    val selected: Boolean = false,
    val bold: Boolean = false,
    val hiddenCount: Int = 0,
)

// Length-prefix the host so IDs containing separators cannot share a preference.
internal fun spaceExpansionKey(hostId: String, spaceId: String): String =
    "space-expanded:${hostId.length}:$hostId:$spaceId"

internal fun spaceTreeExpanded(saved: Boolean?, containsSelection: Boolean): Boolean = saved ?: containsSelection

internal fun Pane.treeTitle(fallbackTitle: String = ""): String = terminal.title.ifBlank { terminal.name.orEmpty() }
    .ifBlank { agent?.program.orEmpty() }.ifBlank { fallbackTitle }.ifBlank { terminal.id }

/** Presentation only: workspace order and membership remain owned by WorkspaceStore. */
internal fun workspaceTreeRows(
    space: Space,
    selectedTerminal: String? = null,
    staleTerminalIds: Set<String> = emptySet(),
    unreadTerminalIds: Set<String> = emptySet(),
    expandedSubAgentTabs: Set<String> = emptySet(),
): List<WorkspaceTreeRow> = buildList {
    fun Pane.stale() = terminal.id in staleTerminalIds || terminal.state == "finished"
    fun terminalRow(kind: WorkspaceTreeKind, tabId: String, tabName: String, depth: Int, pane: Pane) = WorkspaceTreeRow(
        kind, tabId, depth, pane.treeTitle(tabName), pane.terminal.id, pane.agent?.kind,
        pane.terminal.status?.state, pane.terminal.id in unreadTerminalIds,
        stale = pane.stale(), selected = pane.terminal.id == selectedTerminal,
    )
    space.tabs.forEachIndexed { index, tab ->
        val children = tab.panes.flatMap { it.subAgents }.filter { it.agent != null }
            .sortedBy { it.terminal.created_ms }
        val recent = children.takeLast(5).map { it.terminal.id }.toSet()
        // Reveal an older selected child when opened from a notification or the agent screen.
        val hideOlder = children.size > 6 && tab.id !in expandedSubAgentTabs &&
            children.none { it.terminal.id == selectedTerminal && it.terminal.id !in recent }
        val multiplePanes = tab.panes.size > 1
        val opening = tab.openingPane()
        add(WorkspaceTreeRow(
            WorkspaceTreeKind.TAB, tab.id, 1,
            title = tab.displayTitle().ifBlank { tab.name.ifBlank { (index + 1).toString() } },
            terminalId = opening?.terminal?.id, agentKind = opening?.agent?.kind,
            state = tab.aggregateState(), unread = tab.panes.any { it.terminal.id in unreadTerminalIds },
            stale = tab.panes.isNotEmpty() && tab.panes.all { it.stale() },
            selected = !multiplePanes && opening?.terminal?.id == selectedTerminal && selectedTerminal != null,
            bold = tab.containsTerminal(selectedTerminal),
        ))
        tab.panes.forEach { pane ->
            if (multiplePanes) add(terminalRow(WorkspaceTreeKind.PANE, tab.id, tab.name, 2, pane))
            pane.subAgents.filter { it.agent != null && (!hideOlder || it.terminal.id in recent) }
                .sortedWith(compareBy<Pane> { it.stale() }.thenBy { it.terminal.created_ms })
                .forEach { child -> add(terminalRow(WorkspaceTreeKind.SUB_AGENT, tab.id, tab.name, if (multiplePanes) 3 else 2, child)) }
        }
        if (hideOlder) add(WorkspaceTreeRow(WorkspaceTreeKind.MORE, tab.id, 2, hiddenCount = children.size - recent.size))
    }
}
