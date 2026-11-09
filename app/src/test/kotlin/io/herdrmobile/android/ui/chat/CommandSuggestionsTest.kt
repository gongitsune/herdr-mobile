package io.herdrmobile.android.ui.chat

import androidx.compose.ui.text.TextRange
import io.herdrmobile.core.usage.AgentCommand
import io.herdrmobile.core.usage.CommandRun
import io.herdrmobile.core.workspace.AgentKind
import org.junit.Assert.*
import org.junit.Test

class CommandSuggestionsTest {
    @Test fun opensForSupportedAgentFirstTokenOnly() {
        listOf(AgentKind.CLAUDE_CODE, AgentKind.CODEX).forEach { kind ->
            assertEquals(CommandQuery('/', ""), commandQuery("/", kind))
            assertEquals(CommandQuery('/', "mo"), commandQuery("/mo", kind))
            listOf("", "hello /mo", " /mo", "/model ", "/model sonnet", "/model\n", "/model\t", "/model　").forEach {
                assertNull("$kind: $it", commandQuery(it, kind))
            }
        }
        assertNull(commandQuery("/", null))
        AgentKind.entries.filterNot(::supportsChat).forEach { assertNull(commandQuery("/", it)) }
    }

    @Test fun dollarTriggerBelongsToCodexOnly() {
        assertEquals(CommandQuery('$', ""), commandQuery("$", AgentKind.CODEX))
        assertEquals(CommandQuery('$', "review"), commandQuery("\$review", AgentKind.CODEX))
        assertNull(commandQuery("\$review", AgentKind.CLAUDE_CODE))
        assertNull(commandQuery("\$review ", AgentKind.CODEX))
    }

    @Test fun questionsSuppressBothTriggers() {
        assertNull(commandQuery("/model", AgentKind.CLAUDE_CODE, answeringQuestion = true))
        assertNull(commandQuery("\$review", AgentKind.CODEX, answeringQuestion = true))
    }

    @Test fun prefixMatchesComeBeforeSubstringsThenSortBySource() {
        val substring = AgentCommand("team-review", "Review a team", source = "custom")
        val first = AgentCommand("review", "Review code")
        val second = AgentCommand("review-plan", "Review the plan", source = "custom")
        val lastSubstring = AgentCommand("pre-review", "", source = "skill")
        val unrelated = AgentCommand("model", "Includes review only in description")
        val prefixSkill = AgentCommand("review-skill", "", source = "skill")
        val substringBuiltin = AgentCommand("pre-review-builtin", "")
        val all = listOf(lastSubstring, prefixSkill, substring, first, unrelated, second, substringBuiltin)
        assertEquals(listOf(first, second, prefixSkill, substringBuiltin, substring, lastSubstring), filterCommands(all, CommandQuery('/', "ReV")))
        assertEquals(listOf(first, unrelated, substringBuiltin, substring, second, lastSubstring, prefixSkill), filterCommands(all, CommandQuery('/', "")))
        assertTrue(filterCommands(all, CommandQuery('/', "missing")).isEmpty())
    }

    @Test fun filtersByTriggerAndRetainsTerminalMetadata() {
        val builtin = AgentCommand("review", "Builtin", run = CommandRun.TERMINAL)
        val skill = AgentCommand("review", "Skill", "<file>", trigger = '$', source = "skill")
        assertEquals(listOf(builtin), filterCommands(listOf(skill, builtin), CommandQuery('/', "rev")))
        assertEquals(listOf(skill), filterCommands(listOf(builtin, skill), CommandQuery('$', "rev")))
    }

    @Test fun selectionInsertsOnlyTheCommandAndPlacesCursorAtEnd() {
        listOf(CommandRun.CHAT, CommandRun.TERMINAL).forEach { run ->
            val selected = AgentCommand("context", "", run = run).composerValue()
            assertEquals("/context", selected.text)
            assertEquals(TextRange(8), selected.selection)
            assertNull(selected.composition)
        }
    }

    @Test fun selectionWithArgumentsAddsOneSpaceAndPlacesCursorAfterIt() {
        listOf('/', '$').forEach { trigger ->
            val selected = AgentCommand("review", "", "<file> [options]", trigger = trigger).composerValue()
            assertEquals("${trigger}review ", selected.text)
            assertEquals(TextRange(selected.text.length), selected.selection)
            assertNull(selected.composition)
        }
        assertEquals("/context", AgentCommand("context", "", "  ").composerValue().text)
    }
}
