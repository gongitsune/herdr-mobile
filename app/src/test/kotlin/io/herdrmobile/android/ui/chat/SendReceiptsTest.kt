package io.herdrmobile.android.ui.chat

import io.herdrmobile.core.transcript.ChatItem
import org.junit.Assert.*
import org.junit.Test

class SendReceiptsTest {
    private fun sent(id: String = "receipt") = SendReceipt(id, "build", 1000, setOf("old"), "process", true, Delivery.SENT)
    @Test fun acknowledgementAloneIsNeverACompletionClaim() {
        val receipt = sent()
        assertEquals(SendOutcome.SENT, sendOutcome(receipt, true, false))
        val oldLog = listOf(ChatItem.UserMessage("old", "build"))
        assertNull(confirmReceipts(listOf(receipt), oldLog, "process").single().transcriptId)
    }
    @Test fun onlyNewMatchingMessagesFromTheSameVerifiedProcessConfirmDelivery() {
        val rows = listOf(ChatItem.UserMessage("new", "build"))
        assertEquals("new", confirmReceipts(listOf(sent()), rows, "process").single().transcriptId)
        assertNull(confirmReceipts(listOf(sent()), rows, "other-process").single().transcriptId)
        assertNull(confirmReceipts(listOf(sent().copy(baselineComplete = false)), rows, "process").single().transcriptId)
        assertNull(confirmReceipts(listOf(sent()), listOf(ChatItem.UserMessage("new", "different")), "process").single().transcriptId)
    }
    @Test fun changingTheManuallySelectedConversationCannotConfirmAnOldSend() {
        val receipt = sent().copy(bindingPath = "/first/log")
        val rows = listOf(ChatItem.UserMessage("new", "build"))
        assertNull(confirmReceipts(listOf(receipt), rows, "process", "/other/log").single().transcriptId)
        assertEquals("new", confirmReceipts(listOf(receipt), rows, "process", "/first/log").single().transcriptId)
    }
    @Test fun repeatedPromptsMatchDifferentTranscriptRowsInOrder() {
        val receipts = confirmReceipts(listOf(sent("r1"), sent("r2")), listOf(ChatItem.UserMessage("u1", "build"), ChatItem.UserMessage("u2", "build")), "process")
        assertEquals(listOf("u1", "u2"), receipts.map { it.transcriptId })
    }
    @Test fun onlyTheLatestMessageOffersTheWaitingQuestionAndFailuresKeepTheirOwnStatus() {
        assertEquals(SendOutcome.WAITING, sendOutcome(sent(), true, true))
        assertEquals(SendOutcome.SENT, sendOutcome(sent(), false, true))
        assertEquals(SendOutcome.UNDELIVERED, sendOutcome(sent().copy(delivery = Delivery.UNDELIVERED), true, false))
        assertEquals(SendOutcome.TRANSCRIPT, sendOutcome(sent().copy(transcriptId = "u"), false, true))
    }
}
