package io.herdrmobile.android.ui

import io.herdrmobile.core.model.Status
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.workspace.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceTreeTest {
    private fun pane(id: String, children: List<Pane> = emptyList(), agent: Boolean = true,
                     created: Long = 0, finished: Boolean = false, status: String = "idle") = Pane(
        id, Terminal(address = id, created_ms = created, state = if (finished) "finished" else "running",
            status = Status(state = status)),
        if (agent) AgentInfo(AgentKind.CODEX, "codex", emptyList()) else null, children,
    )
    private fun space(vararg tabs: Tab) = Space("space", "Space", "/work", tabs.toList())
    private fun tab(id: String, vararg panes: Pane) = Tab(id, "Tab $id", panes.toList(), explicitName = "Tab $id")

    @Test fun singlePaneIsRepresentedByTabAndOnlyAgentChildrenAreIncluded() {
        val parent = pane("parent", listOf(pane("child"), pane("child-shell", agent = false)))
        val rows = workspaceTreeRows(space(tab("tab", parent)), "parent")
        assertEquals(listOf(WorkspaceTreeKind.TAB, WorkspaceTreeKind.SUB_AGENT), rows.map { it.kind })
        assertEquals(listOf(1, 2), rows.map { it.depth })
        assertEquals(listOf("parent", "child"), rows.map { it.terminalId })
        assertEquals(listOf(true, false), rows.map { it.selected })
        assertTrue(rows.first().bold)
    }

    @Test fun multiplePanesKeepTheirChildrenAndOnlyDeepestSelectedRowIsHighlighted() {
        val s = space(tab("first", pane("shell", agent = false), pane("parent", listOf(pane("child")))),
            tab("second", pane("other")))
        val rows = workspaceTreeRows(s, "child")
        assertEquals(listOf(WorkspaceTreeKind.TAB, WorkspaceTreeKind.PANE, WorkspaceTreeKind.PANE,
            WorkspaceTreeKind.SUB_AGENT, WorkspaceTreeKind.TAB), rows.map { it.kind })
        assertEquals(listOf(1, 2, 2, 3, 1), rows.map { it.depth })
        assertEquals("child", rows.single { it.selected }.terminalId)
        assertEquals("first", rows.single { it.bold }.tabId)
        assertEquals("parent", rows.first().terminalId) // Same opening rule as the tabs sheet.
        assertNull(rows[1].agentKind)
        assertEquals("Tab first", rows[1].title)
        assertEquals("parent", workspaceTreeRows(s, "parent").single { it.selected }.terminalId)
    }

    @Test fun shellParentStillShowsAgentChildrenAndUnnamedEmptyTabsHaveFallbacks() {
        val rows = workspaceTreeRows(space(Tab("empty", "", emptyList()),
            Tab("shell", "2", listOf(pane("shell", listOf(pane("agent")), agent = false)))))
        assertEquals("1", rows.first().title)
        assertNull(rows.first().terminalId)
        assertFalse(rows.first().selected)
        assertNull(rows[1].agentKind)
        assertEquals("agent", rows.last().terminalId)
        assertEquals(2, rows.last().depth)
        assertTrue(workspaceTreeRows(space()).isEmpty())
    }

    @Test fun tabUsesSheetTitleAndAggregateStatusWhileChildrenKeepTheirOwnStatus() {
        val parent = pane("parent", listOf(pane("child", status = "working")), status = "done")
        val titled = parent.copy(terminal = parent.terminal.copy(title = "Implement tree"))
        val rows = workspaceTreeRows(space(Tab("tab", "1", listOf(titled, pane("blocked", status = "blocked")))),
            unreadTerminalIds = setOf("parent"))
        assertEquals("Implement tree", rows.first().title)
        assertEquals("blocked", rows.first().state)
        assertTrue(rows.first().unread)
        assertEquals("working", rows.single { it.kind == WorkspaceTreeKind.SUB_AGENT }.state)
    }

    @Test fun staleAndFinishedChildrenComeLastWithCreationOrderPreservedInEachGroup() {
        val children = listOf(pane("finished", created = 1, finished = true), pane("stale", created = 2),
            pane("new-live", created = 4), pane("old-live", created = 3))
        val rows = workspaceTreeRows(space(tab("tab", pane("parent", children))),
            staleTerminalIds = setOf("stale"), unreadTerminalIds = setOf("new-live"))
            .filter { it.kind == WorkspaceTreeKind.SUB_AGENT }
        assertEquals(listOf("old-live", "new-live", "finished", "stale"), rows.map { it.terminalId })
        assertEquals(listOf(false, false, true, true), rows.map { it.stale })
        assertEquals("new-live", rows.single { it.unread }.terminalId)
    }

    @Test fun staleParentsAndTabAreDimmedWithoutChangingSelection() {
        val rows = workspaceTreeRows(space(tab("tab", pane("parent", listOf(pane("child"))))), "child",
            staleTerminalIds = setOf("parent", "child"))
        assertTrue(rows.all { it.stale })
        assertEquals("child", rows.single { it.selected }.terminalId)
    }

    @Test fun sixChildrenAreVisibleButSevenShowsLatestFiveAndRemainingCount() {
        fun rows(count: Int, expanded: Set<String> = emptySet()) = workspaceTreeRows(
            space(tab("tab", pane("parent", (1..count).map { pane("child$it", created = it.toLong()) }))),
            expandedSubAgentTabs = expanded)
        assertEquals(6, rows(6).count { it.kind == WorkspaceTreeKind.SUB_AGENT })
        assertTrue(rows(6).none { it.kind == WorkspaceTreeKind.MORE })
        assertEquals((3..7).map { "child$it" }, rows(7).filter { it.kind == WorkspaceTreeKind.SUB_AGENT }.map { it.terminalId })
        assertEquals(2, rows(7).single { it.kind == WorkspaceTreeKind.MORE }.hiddenCount)
        assertEquals(7, rows(7, setOf("tab")).count { it.kind == WorkspaceTreeKind.SUB_AGENT })
        assertTrue(rows(7, setOf("tab")).none { it.kind == WorkspaceTreeKind.MORE })
    }

    @Test fun childLimitIsPerTabAcrossPanesAndExpansionDoesNotAffectOtherTabs() {
        val children = (1..7).map { pane("child$it", created = it.toLong()) }
        val s = space(tab("first", pane("p1", children.take(3)), pane("p2", children.drop(3))),
            tab("second", pane("p3", children.map { it.copy(terminal = it.terminal.copy(address = "other-${it.id}")) })))
        val rows = workspaceTreeRows(s, expandedSubAgentTabs = setOf("second"))
        assertEquals(5, rows.count { it.kind == WorkspaceTreeKind.SUB_AGENT && it.tabId == "first" })
        assertEquals("first", rows.single { it.kind == WorkspaceTreeKind.MORE }.tabId)
        assertEquals(7, rows.count { it.kind == WorkspaceTreeKind.SUB_AGENT && it.tabId == "second" })
        assertEquals(3, rows.first { it.kind == WorkspaceTreeKind.SUB_AGENT }.depth)
    }

    @Test fun newestSelectionUsesCreationTimeThenStaleOrderingAndIgnoresShellChildren() {
        val children = (7 downTo 1).map { pane("child$it", created = it.toLong()) } +
            pane("shell", agent = false, created = 100)
        val rows = workspaceTreeRows(space(tab("tab", pane("p", children))), staleTerminalIds = setOf("child3"))
        assertEquals(listOf("child4", "child5", "child6", "child7", "child3"),
            rows.filter { it.kind == WorkspaceTreeKind.SUB_AGENT }.map { it.terminalId })
        assertEquals(2, rows.single { it.kind == WorkspaceTreeKind.MORE }.hiddenCount)
    }

    @Test fun olderSelectedChildIsRevealedAndHighlighted() {
        val s = space(tab("tab", pane("parent", (1..7).map { pane("child$it", created = it.toLong()) })))
        val rows = workspaceTreeRows(s, "child1")
        assertEquals("child1", rows.single { it.selected }.terminalId)
        assertTrue(rows.first().bold)
        assertTrue(rows.none { it.kind == WorkspaceTreeKind.MORE })
    }

    @Test fun savedSpaceChoiceOverridesAutomaticExpansionAndKeysAreScopedByHostAndSpace() {
        assertFalse(spaceTreeExpanded(null, false))
        assertTrue(spaceTreeExpanded(null, true))
        assertFalse(spaceTreeExpanded(false, true))
        assertFalse(spaceTreeExpanded(false, false))
        assertTrue(spaceTreeExpanded(true, false))
        assertNotEquals(spaceExpansionKey("host1", "space"), spaceExpansionKey("host2", "space"))
        assertNotEquals(spaceExpansionKey("host", "space1"), spaceExpansionKey("host", "space2"))
        assertNotEquals(spaceExpansionKey("a:b", "c"), spaceExpansionKey("a", "b:c"))
    }
}
