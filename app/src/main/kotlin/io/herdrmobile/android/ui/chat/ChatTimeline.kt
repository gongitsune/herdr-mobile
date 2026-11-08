package io.herdrmobile.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import io.herdrmobile.android.R
import io.herdrmobile.core.transcript.*

@Composable
fun ChatTimeline(items: List<ChatItem>, blocked: Boolean, canChoose: Boolean, choose: (ChatItem.Question, ChatOption) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberLazyListState()
    val following by remember { derivedStateOf { val info = scroll.layoutInfo; (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2 } }
    LaunchedEffect(items.lastOrNull()) { if (following && items.isNotEmpty()) scroll.animateScrollToItem(items.lastIndex) }
    val latestQuestion = items.filterIsInstance<ChatItem.Question>().lastOrNull()?.id
    LazyColumn(modifier, state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (items.isEmpty()) item { Text(stringResource(R.string.chat_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(items, key = { it.id }) { item ->
            when (item) {
                is ChatItem.UserMessage -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(Modifier.widthIn(max = 560.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                        SelectionContainer { Text(item.text, Modifier.padding(12.dp)) }
                    }
                }
                is ChatItem.AssistantText -> SelectionContainer { Markdown(item.markdown) }
                is ChatItem.Thinking -> CollapsibleCard(stringResource(R.string.thinking), item.id) { SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodySmall) } }
                is ChatItem.ToolCall -> CollapsibleCard(item.name, item.id, stringResource(when (item.state) {
                    ToolState.RUNNING -> R.string.working; ToolState.SUCCEEDED -> R.string.done; ToolState.FAILED -> R.string.error
                })) {
                    SelectionContainer { Text(item.inputSummary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    item.result?.let { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                }
                is ChatItem.Question -> if (blocked && item.id == latestQuestion) OutlinedCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (item.approval) R.string.approval else R.string.question), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                        Markdown(item.prompt)
                        item.options.forEach { option ->
                            OutlinedButton(enabled = canChoose && option.keys.isNotEmpty(), onClick = { choose(item, option) }, modifier = Modifier.fillMaxWidth()) {
                                Column { Text(option.label); option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                            }
                        }
                        if (item.options.any { it.keys.isEmpty() }) Text(stringResource(R.string.unverified_option), style = MaterialTheme.typography.bodySmall)
                    }
                }
                is ChatItem.TurnMarker -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(Modifier.weight(1f).padding(top = 8.dp))
                    Text(item.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.weight(1f).padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun CollapsibleCard(title: String, id: String, status: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, stringResource(if (expanded) R.string.collapse else R.string.expand))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        if (expanded) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
