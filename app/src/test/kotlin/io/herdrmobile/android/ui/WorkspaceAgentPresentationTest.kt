package io.herdrmobile.android.ui

import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.workspace.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentPresentationTest {
    private fun pane(id: String, title: String = "", name: String? = null,
                     kind: AgentKind? = AgentKind.CODEX, children: List<Pane> = emptyList(), finished: Boolean = false) = Pane(
        "p$id", Terminal(address = id, title = title, name = name, state = if (finished) "finished" else "running"),
        kind?.let { AgentInfo(it, if (it == AgentKind.CLAUDE_CODE) "claude" else "codex", emptyList()) }, children,
    )
    private fun tab(vararg panes: Pane, name: String = "Parent tab", explicit: Boolean = true) =
        Tab("tab", name, panes.toList(), if (explicit) name else "")
    private fun space(tab: Tab) = Space("space", "My Space", "/work", listOf(tab))

    @Test fun namesUseTerminalTitleThenNameThenAgentProgramThenTabNameWithoutPaneIds() {
        val t = tab(pane("parent", children = listOf(
            pane("title", title = "Review code", name = "Ignored name"),
            pane("name", name = "Worker"), pane("agent"), pane("shell", kind = null),
        )))
        val links = relatedAgentLinks(t, "title")
        assertEquals(listOf("codex", "Review code", "Worker", "codex", "Parent tab"), links.map { it.title })
        assertTrue(links.none { it.title.startsWith("p") })
        assertEquals("title", links.single { it.selected }.terminalId)
        val tree = workspaceTreeRows(space(t))
        links.drop(1).filter { it.kind != null }.forEach { link ->
            assertEquals(link.title, tree.single { it.kind == WorkspaceTreeKind.SUB_AGENT && it.terminalId == link.terminalId }.title)
        }
    }

    @Test fun parentIsIncludedAndSelectedWhenOpeningParentOrReturningFromChild() {
        val t = tab(pane("parent", children = listOf(pane("child"))))
        val links = relatedAgentLinks(t, "parent")
        assertEquals(listOf("parent", "child"), links.map { it.terminalId })
        assertEquals("parent", links.single { it.selected }.terminalId)
        assertEquals("child", relatedAgentLinks(t, "child").single { it.selected }.terminalId)
    }

    @Test fun linksUseSelectedPanesFamilyAndKeepTerminalIdsDistinctFromPaneIds() {
        val t = tab(pane("other", children = listOf(pane("other-child"))),
            pane("parent", children = listOf(pane("child", kind = AgentKind.CLAUDE_CODE))))
        val links = relatedAgentLinks(t, "child")
        assertEquals(listOf("parent", "child"), links.map { it.terminalId })
        assertEquals(listOf(AgentKind.CODEX, AgentKind.CLAUDE_CODE), links.map { it.kind })
    }

    @Test fun staleMembershipAndFinishedStateDimEachLinkIndependently() {
        val t = tab(pane("parent", children = listOf(pane("live"), pane("stale"), pane("finished", finished = true))))
        val links = relatedAgentLinks(t, "stale", setOf("parent", "stale"))
        assertEquals(listOf(true, false, true, true), links.map { it.stale })
        assertEquals("stale", links.single { it.selected }.terminalId)
        assertFalse(relatedAgentLinks(t, "live").first().stale)
    }

    @Test fun noSwitcherWithoutChildrenOrASelectedFamily() {
        assertTrue(relatedAgentLinks(null, "parent").isEmpty())
        assertTrue(relatedAgentLinks(tab(pane("parent")), "parent").isEmpty())
        assertTrue(relatedAgentLinks(tab(pane("parent", children = listOf(pane("child")))), "missing").isEmpty())
    }

    @Test fun childHeaderUsesChildNameAndKindWithSpaceAndParentTabSubtitle() {
        val t = tab(pane("parent", title = "Parent task", children = listOf(
            pane("child", title = "Review changes", kind = AgentKind.CLAUDE_CODE))))
        val header = workspaceAgentHeader(space(t), t, "child", "Fallback")
        assertEquals("Review changes", header.title)
        assertEquals("My Space · Parent tab", header.subtitle)
        assertEquals(AgentKind.CLAUDE_CODE, header.kind)
        assertEquals(relatedAgentLinks(t, "child").single { it.selected }.title, header.title)
    }

    @Test fun childHeaderUsesParentTaskAsDefaultTabTitleAndFallsBackForUnnamedChild() {
        val t = tab(pane("parent", title = "Parent task", children = listOf(pane("child"))), name = "1", explicit = false)
        val header = workspaceAgentHeader(space(t), t, "child", "Fallback")
        assertEquals("codex", header.title)
        assertEquals("My Space · Parent task", header.subtitle)
        val shellTab = tab(pane("parent", children = listOf(pane("child", kind = null))))
        assertEquals("Parent tab", workspaceAgentHeader(space(shellTab), shellTab, "child", "Fallback").title)
    }

    @Test fun parentHeaderKeepsExistingExplicitAndDefaultTabTitlesAndSpaceSubtitle() {
        val parent = pane("parent", title = "Parent task", children = listOf(pane("child")))
        val named = tab(parent)
        assertEquals(WorkspaceAgentHeader("Parent tab", "My Space", AgentKind.CODEX),
            workspaceAgentHeader(space(named), named, "parent", "Fallback"))
        val numbered = tab(parent, name = "1", explicit = false)
        assertEquals("Parent task", workspaceAgentHeader(space(numbered), numbered, "parent", "Fallback").title)
        assertEquals(WorkspaceAgentHeader("Fallback", "", null), workspaceAgentHeader(null, null, "unplaced", "Fallback"))
    }
}
