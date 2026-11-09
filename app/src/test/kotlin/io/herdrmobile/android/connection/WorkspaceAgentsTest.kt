package io.herdrmobile.android.connection

import io.herdrmobile.android.notifications.StatusTransitions
import io.herdrmobile.android.SavedHost
import io.herdrmobile.core.ConnectionState
import io.herdrmobile.core.model.CoreInfo
import io.herdrmobile.core.model.Status
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.workspace.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentsTest {
    private val agent = AgentInfo(AgentKind.CODEX, "codex", emptyList())
    private fun pane(id: String, state: String = "working", children: List<Pane> = emptyList(), isAgent: Boolean = true) =
        Pane(id, Terminal(address = id, status = Status(state = state)), if (isAgent) agent else null, children)
    private fun project(parent: Pane, staleIds: Set<String> = emptySet()) = workspaceAgents(listOf(HostWorkspace("host", "Host",
        listOf(Space("space", "Space", "/work", listOf(Tab("tab", "Tab", listOf(parent, pane("shell", isAgent = false)))))), null, staleIds)))

    @Test fun onlyWorkspaceAgentsBecomeRowsAndChildrenStayUnderParent() {
        val rows = project(pane("parent", children = listOf(pane("child"), pane("shell-child", isAgent = false))))
        assertEquals(listOf("parent"), rows.map { it.terminal.id })
        assertEquals(listOf("child"), rows.single().subAgents.map { it.terminal.id })
        assertEquals(listOf("parent", "child"), notificationAgents(rows).map { it.terminal.id })
        assertTrue(workspaceAgents(emptyList()).isEmpty())
    }

    @Test fun childTransitionsNotifyAtParentPlaceAndRemovalResetsBaseline() {
        val hosts = listOf(HostStatus(SavedHost(id = "host", name = "Host", host = "host", user = "user"), ConnectionState.Connected(CoreInfo(), emptySet())))
        val tracker = StatusTransitions()
        fun rows(state: String) = notificationAgents(project(pane("parent", children = listOf(pane("child", state)))))
        assertTrue(tracker.update(rows("working"), hosts, true).isEmpty())
        val changed = tracker.update(rows("blocked"), hosts, true).single()
        assertEquals("child", changed.terminal.id)
        assertEquals("Space", changed.spaceName)
        assertEquals("Tab", changed.tabName)
        tracker.update(emptyList(), hosts, true)
        assertTrue(tracker.update(rows("done"), hosts, true).isEmpty())
    }
    @Test fun staleMembershipAppliesToParentsAndChildrenAndClearsWithSnapshot() {
        val parent = pane("parent", children = listOf(pane("stale-child"), pane("live-child")))
        val row = project(parent, setOf("parent", "stale-child")).single()
        assertTrue(row.stale)
        assertEquals(listOf("live-child", "stale-child"), row.subAgents.map { it.terminal.id })
        assertFalse(row.subAgents.first().stale)
        assertTrue(row.subAgents.last().stale)
        assertTrue(notificationAgents(project(parent)).none { it.stale })

        val host = HostWorkspace("host", "Host", listOf(Space("space", "Space", "/work",
            listOf(Tab("tab", "Tab", listOf(parent))))), null, setOf("parent"))
        val rows = workspaceAgents(listOf(host, host.copy(hostId = "other", staleTerminalIds = emptySet())))
        assertTrue(rows.first().stale)
        assertFalse(rows.last().stale)
    }

    @Test fun staleParentAndChildNeverNotifyAndReconnectEstablishesBaseline() {
        val hosts = listOf(HostStatus(SavedHost(id = "host", name = "Host", host = "host", user = "user"), ConnectionState.Connected(CoreInfo(), emptySet())))
        val tracker = StatusTransitions()
        fun rows(state: String, stale: Boolean = false) = notificationAgents(project(
            pane("parent", state, children = listOf(pane("child", state))),
            if (stale) setOf("parent", "child") else emptySet()))
        assertTrue(tracker.update(rows("working"), hosts, true).isEmpty())
        // Even while Connected (waiting for a fresh snapshot), stale rows reset the baseline.
        assertTrue(tracker.update(rows("error", stale = true), hosts, true).isEmpty())
        assertTrue(tracker.update(rows("done"), hosts, true).isEmpty())
        assertEquals(listOf("parent", "child"), tracker.update(rows("blocked"), hosts, true).map { it.terminal.id })
    }

}
