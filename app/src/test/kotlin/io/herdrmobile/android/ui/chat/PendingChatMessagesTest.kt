package io.herdrmobile.android.ui.chat

import io.herdrmobile.core.transcript.ChatItem
import org.junit.Assert.*
import org.junit.Test

class PendingChatMessagesTest {
    @Test fun historicalMatchingPromptsCannotAcknowledgeANewSend() {
        val old = ChatItem.UserMessage("old", "Hello")
        val pending = pendingChatMessage("Hello", listOf(old))
        assertEquals(listOf(pending), reconcilePendingMessages(listOf(pending), listOf(old)))
        assertTrue(reconcilePendingMessages(listOf(pending), listOf(old, old.copy(id = "new"))).isEmpty())
    }

    @Test fun placeholderSurvivesUntilTheMatchingUserMessageAppears() {
        val pending = pendingChatMessage("Do the work", emptyList())
        val unrelated = listOf(ChatItem.AssistantText("a", "Do the work"), ChatItem.UserMessage("u", "Other work"))
        assertEquals(listOf(pending), reconcilePendingMessages(listOf(pending), emptyList()))
        assertEquals(listOf(pending), reconcilePendingMessages(listOf(pending), unrelated))
        assertTrue(reconcilePendingMessages(listOf(pending), unrelated + ChatItem.UserMessage("new", "Do the work")).isEmpty())
    }

    @Test fun aMessageAlreadyLoggedBeforeTheSendCompletesDoesNotLeaveADuplicate() {
        val pending = pendingChatMessage("Hello", emptyList())
        assertTrue(reconcilePendingMessages(listOf(pending), listOf(ChatItem.UserMessage("logged", "Hello"))).isEmpty())
    }

    @Test fun identicalSendsNeedSeparateTranscriptRowsEvenAcrossUpdates() {
        val first = pendingChatMessage("Again", emptyList())
        val second = pendingChatMessage("Again", emptyList())
        assertNotEquals(first.message.id, second.message.id)
        val logged = ChatItem.UserMessage("logged", "Again")
        val remaining = reconcilePendingMessages(listOf(first, second), listOf(logged))
        assertEquals(listOf(second.message), remaining.map { it.message })
        assertEquals(remaining, reconcilePendingMessages(remaining, listOf(logged)))
        assertTrue(reconcilePendingMessages(remaining, listOf(logged, logged.copy(id = "next"))).isEmpty())
    }

    @Test fun transcriptLineEndingsAndOuterWhitespaceDoNotPreventAcknowledgement() {
        val pending = pendingChatMessage("First\r\nSecond\n", emptyList())
        assertEquals("First\r\nSecond\n", pending.message.text)
        assertTrue(reconcilePendingMessages(listOf(pending), listOf(ChatItem.UserMessage("logged", "First\nSecond"))).isEmpty())
    }
}
