package io.herdrmobile.android.ui

import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.workspace.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSelectionTest {
    private fun pane(id: String, agent: Boolean = false, children: List<Pane> = emptyList()) =
        Pane(id, Terminal(address = id), if (agent) AgentInfo(AgentKind.CODEX, "codex", emptyList()) else null, children)
    private fun space(vararg tabs: Tab) = Space("space", "Space", "/work", tabs.toList())

    @Test fun firstAgentInFirstTabWinsOverShellAndLaterAgents() {
        val first = pane("first-agent", true)
        assertEquals(first, space(
            Tab("first", "1", listOf(pane("shell"), first, pane("second-agent", true))),
            Tab("second", "2", listOf(pane("later-agent", true)))
        ).openingPane())
    }

    @Test fun firstAgentInSpaceWinsOverShellInEarlierTab() {
        val shell = pane("shell")
        val agent = pane("agent", true)
        assertEquals(agent, space(
            Tab("first", "1", listOf(shell, pane("second-shell"))),
            Tab("second", "2", listOf(agent))
        ).openingPane())
    }

    @Test fun rememberedTabWinsAndMissingRememberedTabFallsBack() {
        val shell = pane("shell")
        val agent = pane("agent", true)
        val space = space(Tab("shell-tab", "Shell", listOf(shell)), Tab("agent-tab", "Agent", listOf(agent)))
        assertEquals(shell, space.openingPane("shell-tab"))
        assertEquals(agent, space.openingPane("deleted"))
    }

    @Test fun subAgentDoesNotReplaceItsShellParent() {
        val shell = pane("shell", children = listOf(pane("child", true)))
        assertEquals(shell, space(Tab("tab", "1", listOf(shell))).openingPane())
    }

    @Test fun emptyTabsAreSkippedAndSpaceWithoutPanesHasNoTarget() {
        val shell = pane("shell")
        val empty = Tab("empty", "1", emptyList())
        assertEquals(shell, space(empty, Tab("next", "2", listOf(shell))).openingPane())
        assertNull(space(empty).openingPane())
        assertNull(space().openingPane())
        assertNull(empty.openingPane())
    }
}
