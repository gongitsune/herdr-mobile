package io.herdrmobile.android.ui.chat

import io.herdrmobile.core.transcript.ChatItem
import java.util.UUID

data class PendingChatMessage(
    val message: ChatItem.UserMessage,
    val knownUserIds: Set<String>,
)

fun pendingChatMessage(text: String, items: List<ChatItem>): PendingChatMessage = PendingChatMessage(
    ChatItem.UserMessage("pending:${UUID.randomUUID()}", text),
    items.filterIsInstance<ChatItem.UserMessage>().map { it.id }.toSet(),
)

/** Only a new user row can acknowledge a send; repeated prompts consume separate rows. */
fun reconcilePendingMessages(pending: List<PendingChatMessage>, items: List<ChatItem>): List<PendingChatMessage> {
    if (pending.isEmpty()) return pending
    val remaining = pending.toMutableList()
    val acknowledged = mutableSetOf<String>()
    items.filterIsInstance<ChatItem.UserMessage>().forEach { item ->
        val index = remaining.indexOfFirst {
            item.id !in it.knownUserIds && it.message.text.normalizedPrompt() == item.text.normalizedPrompt()
        }
        if (index >= 0) {
            remaining.removeAt(index)
            acknowledged += item.id
        }
    }
    return if (acknowledged.isEmpty()) remaining else remaining.map { it.copy(knownUserIds = it.knownUserIds + acknowledged) }
}

private fun String.normalizedPrompt(): String = replace("\r\n", "\n").trim()
