package io.herdrmobile.android.ui.chat

import io.herdrmobile.core.transcript.ChatItem

enum class Delivery { PENDING, SENT, UNDELIVERED }
enum class SendOutcome { SENT, WAITING, TRANSCRIPT, UNDELIVERED }
data class SendReceipt(
    val id: String, val text: String, val sentAtEpochMs: Long, val baselineUserIds: Set<String>,
    val processKey: String?, val baselineComplete: Boolean,
    val delivery: Delivery = Delivery.PENDING, val transcriptId: String? = null, val bindingPath: String? = null,
)
fun confirmReceipts(receipts: List<SendReceipt>, items: List<ChatItem>, processKey: String?, bindingPath: String? = null): List<SendReceipt> {
    val used = receipts.mapNotNull { it.transcriptId }.toMutableSet()
    return receipts.map { receipt ->
        if (receipt.transcriptId != null || !receipt.baselineComplete || processKey == null || receipt.processKey != processKey || (receipt.bindingPath != null && receipt.bindingPath != bindingPath)) receipt
        else {
            val matched = items.filterIsInstance<ChatItem.UserMessage>().firstOrNull {
                it.id !in used && it.id !in receipt.baselineUserIds && it.text.trim() == receipt.text.trim()
            }
            matched?.let { used += it.id; receipt.copy(transcriptId = it.id) } ?: receipt
        }
    }
}
fun sendOutcome(receipt: SendReceipt, latest: Boolean, blocked: Boolean): SendOutcome? = when {
    receipt.transcriptId != null && latest && blocked -> SendOutcome.WAITING
    receipt.transcriptId != null -> SendOutcome.TRANSCRIPT
    receipt.delivery == Delivery.UNDELIVERED -> SendOutcome.UNDELIVERED
    receipt.delivery == Delivery.PENDING -> null
    latest && blocked -> SendOutcome.WAITING
    else -> SendOutcome.SENT
}
