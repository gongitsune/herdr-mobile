package io.herdrmobile.android.ui.chat

import io.herdrmobile.android.ui.terminal.DetailSurface
import io.herdrmobile.core.transcript.*
import org.junit.Assert.*
import org.junit.Test

class ChatPresentationTest {
    private val option = ChatOption("Allow", keys = listOf("Enter"))
    private val question = ChatItem.Question("approval", "Allow?", listOf(option), approval = true)
    @Test fun defaultSurfaceFollowsLogAvailabilityUntilUserChooses() {
        assertEquals(DetailSurface.TERMINAL, detailSurface(null, emptyList()))
        assertEquals(DetailSurface.CHAT, detailSurface(null, listOf(ChatItem.UserMessage("u", "hello"))))
        assertEquals(DetailSurface.TERMINAL, detailSurface(DetailSurface.TERMINAL, listOf(question)))
        assertEquals(DetailSurface.CHAT, detailSurface(DetailSurface.CHAT, emptyList()))
    }
    @Test fun unverifiedOrDisconnectedLogsDoNotDisplayAsTheCurrentConversation() {
        val items = listOf(question)
        assertTrue(visibleChatItems(TranscriptState.Loading, items).isEmpty())
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
}
