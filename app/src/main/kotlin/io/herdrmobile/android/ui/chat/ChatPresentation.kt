package io.herdrmobile.android.ui.chat

import io.herdrmobile.android.ui.terminal.DetailSurface
import io.herdrmobile.core.transcript.*

fun detailSurface(selected: DetailSurface?, items: List<ChatItem>): DetailSurface = selected ?: if (items.isEmpty()) DetailSurface.TERMINAL else DetailSurface.CHAT

fun canChooseOption(items: List<ChatItem>, question: ChatItem.Question, option: ChatOption, blocked: Boolean, interactive: Boolean, verifiedTranscript: Boolean): Boolean =
    blocked && interactive && verifiedTranscript && option.keys.isNotEmpty() &&
        items.filterIsInstance<ChatItem.Question>().lastOrNull() == question && option in question.options

/** Logs without a current verified binding must not appear to belong to this process. */
fun visibleChatItems(state: TranscriptState?, items: List<ChatItem>): List<ChatItem> = if (state is TranscriptState.Ready) items else emptyList()
