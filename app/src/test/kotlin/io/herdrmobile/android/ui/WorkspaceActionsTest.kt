package io.herdrmobile.android.ui

import io.herdrmobile.android.connection.ConsoleTerminal
import io.herdrmobile.core.model.Status
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.workspace.Pane
import io.herdrmobile.core.workspace.Tab
import org.junit.Assert.*
import org.junit.Test

class WorkspaceActionsTest {
    private fun pane(id: String, state: String? = null, children: List<Pane> = emptyList()) = Pane(id, Terminal(address = id, status = state?.let { Status(state = it) }), subAgents = children)
    @Test fun tabStatePrioritizesBlockedWorkingAndDoneRegardlessOfOrder() {
        fun tab(vararg states: String) = Tab("tab", "Tab", states.mapIndexed { i, state -> pane("$i", state) })
        assertEquals("blocked", tab("idle", "done", "working", "blocked").aggregateState())
        assertEquals("error", tab("working", "error", "done").aggregateState())
        assertEquals("working", tab("done", "idle", "working").aggregateState())
        assertEquals("done", tab("idle", "done").aggregateState())
        assertNull(Tab("empty", "", emptyList()).aggregateState())
        assertNull(Tab("shell", "", listOf(pane("shell"))).aggregateState())
    }
    @Test fun closeTargetsOnlyDistinctTopLevelTerminals() {
        val parent = pane("parent", children = listOf(pane("child", "blocked")))
        val tab = Tab("tab", "Tab", listOf(parent, parent, pane("shell")))
        assertEquals(listOf("parent", "shell"), tab.terminalIds())
        assertNull(tab.aggregateState())
    }
    @Test fun closeContinuesAfterErrorsAndIgnoresMissingTerminals() = kotlinx.coroutines.runBlocking {
        val attempted = mutableListOf<String>()
        val errors = closeTabTerminals(listOf("one", "gone", "ended", "terminalEnded", "two", "ok")) { id ->
            attempted += id
            if (id == "gone") throw io.herdrmobile.core.rpc.HcError("not_found", "gone")
            if (id == "ended") throw io.herdrmobile.core.rpc.HcError("not_live", "ended")
            if (id == "terminalEnded") throw io.herdrmobile.core.rpc.HcError("terminal_not_live", "ended")
            if (id != "ok") throw io.herdrmobile.core.rpc.HcError("io", "failed")
        }
        assertEquals(listOf("one", "gone", "ended", "terminalEnded", "two", "ok"), attempted)
        assertEquals(listOf("one", "two"), errors.map { it.first })
    }
    @Test fun closePropagatesCancellation() = kotlinx.coroutines.runBlocking {
        try {
            closeTabTerminals(listOf("one")) { throw kotlinx.coroutines.CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }
    @Test fun defaultTabTitleUsesAgentThenTerminalAndKeepsNamedTabs() {
        val shell = Pane("shell", Terminal(address = "shell", title = "Shell title"))
        val agent = Pane("agent", Terminal(address = "agent", title = "Agent title"),
            io.herdrmobile.core.workspace.AgentInfo(io.herdrmobile.core.workspace.AgentKind.CODEX, "codex", emptyList()))
        assertEquals("Agent title", Tab("tab", "1", listOf(shell, agent)).displayTitle())
        assertEquals("Shell title", Tab("tab", "2", listOf(shell)).displayTitle())
        assertEquals("123", Tab("tab", "123", listOf(agent), explicitName = "123").displayTitle())
        assertEquals("Custom", Tab("tab", "Custom", listOf(agent), explicitName = "Custom").displayTitle())
        assertTrue(Tab("tab", "1", listOf(pane("parent", children = listOf(pane("child"))))).containsTerminal("child"))
    }
    @Test fun renameOnlyWritesChangedExplicitNames() {
        assertFalse(renameChanged("", ""))
        assertFalse(renameChanged("", "  "))
        assertFalse(renameChanged("123", " 123 "))
        assertTrue(renameChanged("", "123"))
        assertTrue(renameChanged("Custom", ""))
    }
    @Test fun renameErrorsUseSpecificMessages() {
        assertEquals(io.herdrmobile.android.R.string.rename_conflict, renameErrorResource("conflict"))
        assertEquals(io.herdrmobile.android.R.string.rename_not_in_layout, renameErrorResource("not_in_layout"))
        assertEquals(io.herdrmobile.android.R.string.rename_disconnected, renameErrorResource("disconnected"))
        assertEquals(io.herdrmobile.android.R.string.rename_failed, renameErrorResource("unsupported"))
    }
    @Test fun agentsWithWorkOrUnreadDoneAreActiveAndOthersRest() {
        fun row(state: String?, stale: Boolean = false) = ConsoleTerminal("host", "Host", pane("agent", state).terminal, stale)
        for (state in listOf("blocked", "error", "working")) assertTrue(row(state).needsAttention(false))
        assertTrue(row("done").needsAttention(true))
        assertFalse(row("done").needsAttention(false))
        assertFalse(row("idle").needsAttention(true))
        assertFalse(row(null).needsAttention(true))
        assertFalse(row("blocked", true).needsAttention(true))
    }
}
