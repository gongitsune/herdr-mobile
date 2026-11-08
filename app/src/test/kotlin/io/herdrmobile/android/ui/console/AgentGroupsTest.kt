package io.herdrmobile.android.ui.console

import io.herdrmobile.android.connection.ConsoleTerminal
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.model.Status
import org.junit.Assert.*
import org.junit.Test

class AgentGroupsTest {
    @Test fun errorsNeedAttentionAndEveryTerminalAppearsExactlyOnce() {
        val rows = listOf("idle", "done", "working", "error", "blocked", "unknown").mapIndexed { i, status ->
            ConsoleTerminal("host", "Host", Terminal(address = "$i", status = Status(state = status)))
        }
        val groups = groupAgents(rows)
        assertEquals(listOf(AgentGroup.BLOCKED, AgentGroup.WORKING, AgentGroup.DONE, AgentGroup.IDLE), groups.keys.toList())
        assertEquals(setOf("error", "blocked"), groups.getValue(AgentGroup.BLOCKED).map { it.terminal.status!!.state }.toSet())
        assertEquals(rows.size, groups.values.flatten().distinctBy { it.terminal.id }.size)
    }
}
