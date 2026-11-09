package io.herdrmobile.android.ui.chat

import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.workspace.AgentDetector
import io.herdrmobile.core.workspace.AgentKind

fun supportsChat(kind: AgentKind?): Boolean = kind == AgentKind.CLAUDE_CODE || kind == AgentKind.CODEX

fun chatAgentKind(terminal: Terminal?): AgentKind? = terminal?.let { AgentDetector.detect(it)?.kind }?.takeIf(::supportsChat)

fun canChooseOption(items: List<ChatItem>, question: ChatItem.Question, option: ChatOption, blocked: Boolean, interactive: Boolean, verifiedTranscript: Boolean): Boolean =
    blocked && interactive && verifiedTranscript && option.keys.isNotEmpty() &&
        activeQuestion(items, blocked) == question && option in question.options

/** Logs without a current verified binding must not appear to belong to this process. */
fun visibleChatItems(state: TranscriptState?, items: List<ChatItem>): List<ChatItem> = if (state is TranscriptState.Ready) items else emptyList()

/** Questions from an earlier prompt must never control the current composer. */
fun activeQuestion(items: List<ChatItem>, blocked: Boolean): ChatItem.Question? {
    if (!blocked) return null
    val turn = (projectTurns(items, running = true).lastOrNull { it is TimelineEntry.Turn } as? TimelineEntry.Turn)?.value ?: return null
    return turn.questions.lastOrNull().takeUnless { turn.recordEnded }
}

fun numberedOption(question: ChatItem.Question, input: String): ChatOption? {
    val value = java.text.Normalizer.normalize(input.trim(), java.text.Normalizer.Form.NFKC)
    if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
    val number = value.toIntOrNull() ?: return null
    return question.options.getOrNull(number - 1)
}

fun canStopWorking(working: Boolean, blocked: Boolean, question: ChatItem.Question?, coolingDown: Boolean): Boolean =
    working && !blocked && question == null && !coolingDown
