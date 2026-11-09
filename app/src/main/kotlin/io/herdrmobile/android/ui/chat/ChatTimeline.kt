package io.herdrmobile.android.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.herdrmobile.android.R
import io.herdrmobile.core.transcript.*

@Composable
fun ChatTimeline(items: List<ChatItem>, blocked: Boolean,
    modifier: Modifier = Modifier, running: Boolean = false, runningElapsed: String? = null, loading: Boolean = false) {
    val scroll = rememberLazyListState()
    val entries = remember(items, running, blocked) { projectTurns(items, running || blocked) }
    val following by remember { derivedStateOf { val info = scroll.layoutInfo; (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2 } }
    LaunchedEffect(items.lastOrNull()) {
        if (following && entries.isNotEmpty()) scroll.animateScrollToItem(entries.lastIndex)
    }
    val latestQuestion = items.filterIsInstance<ChatItem.Question>().lastOrNull()?.id
    LazyColumn(modifier, state = scroll, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (entries.isEmpty() && !loading) item { Text(stringResource(R.string.chat_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(entries, key = { it.id }) { entry -> when (entry) {
            is TimelineEntry.Notice -> Text(stringResource(if (entry.marker.label == "Context compacted") R.string.context_compacted else R.string.history_missing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is TimelineEntry.Turn -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val turn = entry.value
                turn.prompt?.let { prompt -> UserBubble(prompt.text) }
                if (turn.work.isNotEmpty() || !turn.completed) WorkBlock(turn, blocked && turn.questions.any { it.id == latestQuestion }, runningElapsed)
                turn.answers.forEach { answer -> key(answer.id) { SelectionContainer { ChatMarkdown(answer.markdown) } } }
            }
        } }

    }
}

@Composable
private fun UserBubble(text: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Surface(Modifier.widthIn(max = maxWidth * 0.8f).align(androidx.compose.ui.Alignment.CenterEnd), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            SelectionContainer { Text(text, Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) }
        }
    }
}

@Composable
fun QuestionCard(question: ChatItem.Question, canChoose: Boolean, choose: (ChatItem.Question, ChatOption) -> Unit) {
    var expanded by remember(question.id) { mutableStateOf(true) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(question.prompt, Modifier.weight(1f), color = if (question.approval) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(if (expanded) "⌄" else "›")
            }
            if (expanded) {
                question.options.forEachIndexed { index, option ->
                    TextButton(enabled = canChoose && option.keys.isNotEmpty(), onClick = { choose(question, option) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) { Text("${index + 1}  ${option.label}"); if (option.keys.isEmpty()) Text(stringResource(R.string.choice_manual), style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}
