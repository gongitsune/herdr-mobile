package io.herdrmobile.android.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import io.herdrmobile.android.R
import io.herdrmobile.core.transcript.*

@Composable
fun ChatTimeline(items: List<ChatItem>, blocked: Boolean, canChoose: Boolean, choose: (ChatItem.Question, ChatOption) -> Unit,
    modifier: Modifier = Modifier, running: Boolean = false, runningElapsed: String? = null, receipts: List<SendReceipt> = emptyList(), questionJump: Int = 0,
    answerQuestion: () -> Unit = {}, liveContent: (@Composable () -> Unit)? = null) {
    val scroll = rememberLazyListState()
    val entries = remember(items, running) { projectTurns(items, running || blocked) }
    val following by remember { derivedStateOf { val info = scroll.layoutInfo; (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2 } }
    val local = receipts.filter { it.transcriptId == null }
    val footer = if (liveContent != null) 1 else 0
    LaunchedEffect(items.lastOrNull(), local.lastOrNull()?.id, liveContent != null) {
        val last = entries.size + local.size + footer - 1
        if (following && last >= 0) scroll.animateScrollToItem(last)
    }
    LaunchedEffect(questionJump) {
        if (questionJump > 0) entries.indexOfLast { (it as? TimelineEntry.Turn)?.value?.questions?.isNotEmpty() == true }.takeIf { it >= 0 }?.let { scroll.animateScrollToItem(it) }
    }
    val latestQuestion = items.filterIsInstance<ChatItem.Question>().lastOrNull()?.id
    LazyColumn(modifier, state = scroll, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (entries.isEmpty() && local.isEmpty() && liveContent == null) item { Text(stringResource(R.string.chat_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(entries, key = { it.id }) { entry -> when (entry) {
            is TimelineEntry.Notice -> Text(stringResource(if (entry.marker.label == "Context compacted") R.string.context_compacted else R.string.history_missing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is TimelineEntry.Turn -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val turn = entry.value
                turn.prompt?.let { prompt -> UserBubble(prompt.text, receipts.find { it.transcriptId == prompt.id }, receipts.lastOrNull()?.id, blocked, answerQuestion) }
                if (turn.work.isNotEmpty()) WorkBlock(turn, blocked && turn.questions.any { it.id == latestQuestion }, runningElapsed)
                turn.answers.forEach { answer -> key(answer.id) { SelectionContainer { Markdown(answer.markdown) } } }
                turn.questions.filter { blocked && it.id == latestQuestion }.forEach { question -> QuestionCard(question, canChoose, choose) }
            }
        } }
        items(local, key = { "sent:${it.id}" }) { receipt -> UserBubble(receipt.text, receipt, receipts.lastOrNull()?.id, blocked, answerQuestion) }
        if (liveContent != null) item(key = "live-terminal") { liveContent() }
    }
}

@Composable
private fun UserBubble(text: String, receipt: SendReceipt?, latestId: String?, blocked: Boolean, answerQuestion: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.End) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Surface(Modifier.widthIn(max = maxWidth * 0.8f).align(androidx.compose.ui.Alignment.CenterEnd), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                SelectionContainer { Text(text, Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) }
            }
        }
        receipt?.let { sendOutcome(it, it.id == latestId, blocked) }?.let { outcome ->
            val label = stringResource(when (outcome) { SendOutcome.SENT -> R.string.receipt_sent; SendOutcome.WAITING -> R.string.receipt_waiting; SendOutcome.TRANSCRIPT -> R.string.receipt_transcript; SendOutcome.UNDELIVERED -> R.string.receipt_undelivered })
            if (outcome == SendOutcome.WAITING) TextButton(onClick = answerQuestion, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 40.dp)) { Text(label, style = MaterialTheme.typography.labelSmall) }
            else Text(label, Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuestionCard(question: ChatItem.Question, canChoose: Boolean, choose: (ChatItem.Question, ChatOption) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(if (question.approval) R.string.approval else R.string.question), style = MaterialTheme.typography.labelLarge)
            Markdown(question.prompt)
            question.options.forEach { option -> OutlinedButton(enabled = canChoose && option.keys.isNotEmpty(), onClick = { choose(question, option) }, modifier = Modifier.fillMaxWidth()) {
                Column { Text(option.label); option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
            } }
            if (question.options.any { it.keys.isEmpty() }) Text(stringResource(R.string.unverified_option), style = MaterialTheme.typography.bodySmall)
        }
    }
}
