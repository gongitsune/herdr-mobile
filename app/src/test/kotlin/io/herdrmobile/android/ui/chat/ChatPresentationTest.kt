package io.herdrmobile.android.ui.chat

import io.herdrmobile.core.model.Foreground
import io.herdrmobile.core.model.Status
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.workspace.AgentKind
import org.junit.Assert.*
import org.junit.Test

class ChatPresentationTest {
    private val option = ChatOption("Allow", keys = listOf("Enter"))
    private val question = ChatItem.Question("approval", "Allow?", listOf(option), approval = true)
    @Test fun onlyClaudeAndCodexHaveSurfaceSegments() {
        (AgentKind.entries + listOf(null)).forEach { kind ->
            val supported = kind == AgentKind.CLAUDE_CODE || kind == AgentKind.CODEX
            assertEquals("segments for $kind", supported, supportsChat(kind))
        }
    }
    @Test fun transcriptEligibilityFollowsForegroundIncludingAgentExitToShell() {
        val terminal = Terminal(argv = listOf("codex"), pid = 10,
            foreground = Foreground(argv = listOf("bash")), status = Status(app = "codex", state = "idle"))
        assertNull(chatAgentKind(terminal))
        val codex = terminal.copy(foreground = Foreground(argv = listOf("codex"), pid = 20))
        assertEquals(AgentKind.CODEX, chatAgentKind(codex))
        assertEquals(AgentKind.CLAUDE_CODE, chatAgentKind(codex.copy(foreground = Foreground(argv = listOf("claude")))))
        assertNull(chatAgentKind(terminal))
        assertNull(chatAgentKind(codex.copy(state = "finished")))
        assertNull(chatAgentKind(codex.copy(pid = null, reason = "exited")))
        assertNull(chatAgentKind(codex.copy(foreground = Foreground(argv = listOf("gemini")))))
        assertNull(chatAgentKind(null))
    }
    @Test fun unverifiedOrDisconnectedLogsDoNotDisplayAsTheCurrentConversation() {
        val items = listOf(question)
        assertTrue(visibleChatItems(TranscriptState.Loading, items).isEmpty())
        assertTrue(visibleChatItems(TranscriptState.NotStarted(AgentKind.CODEX), items).isEmpty())
        assertTrue(visibleChatItems(TranscriptState.Unavailable("Process changed"), items).isEmpty())
        val binding = TranscriptBinding(io.herdrmobile.core.workspace.AgentKind.CODEX, "session", "/log", "process")
        assertEquals(items, visibleChatItems(TranscriptState.Ready(binding, true), items))
    }
    @Test fun approvalsRequireLiveBlockedInteractiveVerifiedContextAndKnownKeys() {
        val items = listOf(question)
        assertTrue(canChooseOption(items, question, option, true, true, true))
        assertFalse(canChooseOption(items, question, option, false, true, true))
        assertFalse(canChooseOption(items, question, option, true, false, true))
        assertFalse(canChooseOption(items, question, option, true, true, false))
        assertFalse(canChooseOption(items, question, option.copy(keys = emptyList()), true, true, true))
        assertFalse(canChooseOption(items + question.copy(id = "new"), question, option, true, true, true))
    }
    @Test fun numberedAnswersDistinguishManualOptionsFromInvalidNumbers() {
        val q = question.copy(options = listOf(option, option.copy(label = "No", keys = listOf("Escape")), option.copy(keys = emptyList())))
        assertEquals(q.options[1], numberedOption(q, " 2 "))
        assertEquals(q.options[1], numberedOption(q, " ２ "))
        assertEquals(q.options[2], numberedOption(q, "３"))
        for (input in listOf("0", "-1", "+1", "4", "999999999999999", "yes", "1 hello")) assertNull(numberedOption(q, input))
    }
    @Test fun stopIsUnavailableDuringApprovalAndAfterSendingEvenWithStaleRunningActivity() {
        assertTrue(canStopWorking(true, false, null, false))
        assertFalse(canStopWorking(true, true, null, false))
        assertFalse(canStopWorking(true, false, question, false))
        assertFalse(canStopWorking(true, false, null, true))
        assertFalse(canStopWorking(false, false, null, false))
    }
    @Test fun aQuestionFromAnEarlierPromptCannotBecomeThePinnedCard() {
        assertEquals(question, activeQuestion(listOf(question), true))
        val nextTurn = listOf(question, ChatItem.UserMessage("new", "Next task"))
        assertNull(activeQuestion(nextTurn, true))
        assertFalse(canChooseOption(nextTurn, question, option, true, true, true))
        assertNull(activeQuestion(listOf(question), false))
    }
}
