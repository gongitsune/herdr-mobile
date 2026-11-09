package io.herdrmobile.android.connection

/** Workspace membership is authoritative for both the Agents list and notifications. */
internal fun workspaceAgents(hosts: List<HostWorkspace>): List<ConsoleTerminal> = hosts.flatMap { host ->
    host.spaces.flatMap { space ->
        space.tabs.flatMap { tab ->
            tab.panes.mapNotNull { pane ->
                if (pane.agent == null) return@mapNotNull null
                val children = pane.subAgents.filter { it.agent != null }.map { child ->
                    ConsoleTerminal(host.hostId, host.hostName, child.terminal, stale = child.terminal.id in host.staleTerminalIds,
                        spaceName = space.name, tabName = tab.name)
                }
                ConsoleTerminal(host.hostId, host.hostName, pane.terminal, stale = pane.terminal.id in host.staleTerminalIds,
                    subAgents = children.sortedBy { it.stale },
                    spaceName = space.name, tabName = tab.name)
            }
        }
    }
}.distinctBy { it.hostId to it.terminal.id }

internal fun notificationAgents(rows: List<ConsoleTerminal>): List<ConsoleTerminal> =
    rows.flatMap { listOf(it) + it.subAgents }.distinctBy { it.hostId to it.terminal.id }
