package io.herdrmobile.android.ui.console

import io.herdrmobile.android.connection.ConsoleTerminal

enum class AgentGroup { BLOCKED, WORKING, DONE, IDLE }
fun groupAgents(rows: List<ConsoleTerminal>): Map<AgentGroup, List<ConsoleTerminal>> = AgentGroup.entries.associateWith { group ->
    rows.filter { row ->
        val value = when (row.terminal.status?.state) {
            "blocked", "error" -> AgentGroup.BLOCKED
            "working" -> AgentGroup.WORKING
            "done" -> AgentGroup.DONE
            else -> AgentGroup.IDLE
        }
        value == group
    }.sortedWith(compareBy<ConsoleTerminal> { it.stale }.thenBy { it.hostName }.thenBy { it.terminal.id })
}
