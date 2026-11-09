package io.herdrmobile.android.ui.chat

import io.herdrmobile.core.transcript.*

sealed interface WorkRun {
    data class Tools(val calls: List<ChatItem.ToolCall>) : WorkRun
    data class Prose(val item: ChatItem.AssistantText) : WorkRun
    data class Thought(val item: ChatItem.Thinking) : WorkRun
}
data class ChatTurn(
    val id: String, val prompt: ChatItem.UserMessage?, val work: List<WorkRun>,
    val answers: List<ChatItem.AssistantText>, val questions: List<ChatItem.Question>,
    val completed: Boolean, val durationMs: Long?, val edits: Int, val commands: Int, val failures: Int, val recordEnded: Boolean = false,
)
sealed interface TimelineEntry {
    val id: String
    data class Turn(val value: ChatTurn) : TimelineEntry { override val id = "turn:${value.id}" }
    data class Notice(val marker: ChatItem.TurnMarker) : TimelineEntry { override val id = "notice:${marker.id}" }
}

fun groupWork(items: List<ChatItem>): List<WorkRun> {
    val result = mutableListOf<WorkRun>()
    val tools = mutableListOf<ChatItem.ToolCall>()
    fun flush() { if (tools.isNotEmpty()) { result += WorkRun.Tools(tools.toList()); tools.clear() } }
    items.forEach { item -> when (item) {
        is ChatItem.ToolCall -> tools += item
        is ChatItem.AssistantText -> { flush(); result += WorkRun.Prose(item) }
        is ChatItem.Thinking -> { flush(); result += WorkRun.Thought(item) }
        else -> flush()
    } }
    flush(); return result
}
private fun ChatItem.time(): Long? = atEpochMs

/** Stable logical turns; ordinary start/end markers never become visual separators. */
fun projectTurns(items: List<ChatItem>, running: Boolean): List<TimelineEntry> {
    data class Raw(val rows: List<ChatItem>, val closed: Boolean, val recordedEnd: Boolean)
    val raw = mutableListOf<Any>()
    val current = mutableListOf<ChatItem>()
    fun flush(closed: Boolean, recordedEnd: Boolean = closed) { if (current.any { it !is ChatItem.TurnMarker }) raw += Raw(current.toList(), closed, recordedEnd); current.clear() }
    items.forEach { item ->
        when {
            item is ChatItem.UserMessage -> { flush(true); current += item }
            item is ChatItem.TurnMarker && item.label.lowercase() in setOf("turn complete", "turn interrupted") -> { current += item; flush(true) }
            item is ChatItem.TurnMarker && item.label.equals("Turn started", true) -> {
                if (current.any { it is ChatItem.ToolCall || it is ChatItem.AssistantText || it is ChatItem.Thinking }) flush(true)
                current += item
            }
            item is ChatItem.TurnMarker && item.label in setOf("Context compacted", "Earlier rollout history is unavailable") -> { flush(true); raw += TimelineEntry.Notice(item) }
            item is ChatItem.TurnMarker -> Unit
            else -> current += item
        }
    }
    flush(!running, recordedEnd = false)
    val lastTurn = raw.indexOfLast { it is Raw }
    return raw.mapIndexed { index, value ->
        if (value is TimelineEntry.Notice) value else {
            val turn = value as Raw
            val rows = turn.rows
            val prompt = rows.filterIsInstance<ChatItem.UserMessage>().firstOrNull()
            val lastAction = rows.indexOfLast { it is ChatItem.ToolCall || it is ChatItem.Thinking }
            val suffix = rows.drop(lastAction + 1).filterIsInstance<ChatItem.AssistantText>()
            val answers = suffix.ifEmpty { if (turn.recordedEnd) rows.filterIsInstance<ChatItem.AssistantText>().takeLast(1) else emptyList() }
            val answerIds = answers.map { it.id }.toSet()
            val work = rows.filter { it is ChatItem.ToolCall || it is ChatItem.Thinking || (it is ChatItem.AssistantText && it.id !in answerIds) }
            val tools = rows.filterIsInstance<ChatItem.ToolCall>()
            val start = prompt?.atEpochMs ?: rows.firstNotNullOfOrNull { it.time() }
            val next = raw.drop(index + 1).filterIsInstance<Raw>().firstOrNull()?.rows?.firstNotNullOfOrNull { it.time() }
            val end = rows.filterIsInstance<ChatItem.TurnMarker>().lastOrNull()?.atEpochMs ?: next ?: answers.lastOrNull()?.atEpochMs
            val duration = if (start != null && end != null && end >= start) end - start else null
            TimelineEntry.Turn(ChatTurn(prompt?.id ?: rows.first().id, prompt, groupWork(work), answers,
                rows.filterIsInstance<ChatItem.Question>(), turn.closed || index != lastTurn, duration,
                tools.count { it.verb in setOf("Edited", "Wrote") }, tools.count { it.verb == "Ran" }, tools.count { it.state == ToolState.FAILED }, turn.recordedEnd))
        }
    }
}

fun elapsedLabel(startedAtEpochMs: Long?, nowEpochMs: Long): String? {
    if (startedAtEpochMs == null) return null
    val seconds = ((nowEpochMs - startedAtEpochMs).coerceAtLeast(0) / 1000)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

fun workStartsExpanded(turn: ChatTurn): Boolean = !turn.completed || (turn.answers.isEmpty() && turn.work.any { it is WorkRun.Prose })
