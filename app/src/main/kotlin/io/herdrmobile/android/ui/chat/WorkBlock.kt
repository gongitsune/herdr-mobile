package io.herdrmobile.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import io.herdrmobile.android.R
import io.herdrmobile.core.transcript.*

@Composable
fun WorkBlock(turn: ChatTurn, blocked: Boolean, elapsed: String? = null) {
    var preference by rememberSaveable(turn.id) { mutableStateOf<Boolean?>(null) }
    val running = !turn.completed
    val expanded = preference ?: workStartsExpanded(turn)
    val summary = buildList {
        if (turn.edits > 0) add(pluralStringResource(R.plurals.work_edits, turn.edits, turn.edits))
        if (turn.commands > 0) add(pluralStringResource(R.plurals.work_commands, turn.commands, turn.commands))
        if (turn.edits == 0 && turn.commands == 0) {
            val tools = turn.work.filterIsInstance<WorkRun.Tools>().sumOf { it.calls.size }
            if (tools > 0) add(pluralStringResource(R.plurals.work_tools, tools, tools))
        }
    }.joinToString(" · ")
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { preference = !expanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, stringResource(if (expanded) R.string.collapse else R.string.expand), Modifier.size(20.dp))
            val title = when {
                running && blocked -> stringResource(R.string.needs_you)
                running -> elapsed?.let { stringResource(R.string.working_elapsed, it) } ?: stringResource(R.string.working_ellipsis)
                turn.durationMs != null -> stringResource(R.string.worked_for, turn.durationMs / 1000)
                else -> stringResource(R.string.worked)
            }
            Text(if (summary.isBlank()) title else "$title · $summary", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (turn.failures > 0) Text(pluralStringResource(R.plurals.work_failures, turn.failures, turn.failures), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) turn.work.forEach { run -> when (run) {
            is WorkRun.Tools -> ToolRun(run.calls)
            is WorkRun.Prose -> if (running) Markdown(run.item.markdown) else Text(run.item.markdown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is WorkRun.Thought -> Thought(run.item)
        } }
    }
}

@Composable
private fun ToolRun(calls: List<ChatItem.ToolCall>) {
    var expanded by rememberSaveable(calls.first().id) { mutableStateOf(true) }
    if (calls.size > 1) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 40.dp)) {
        Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(18.dp))
        Text(pluralStringResource(R.plurals.work_tools, calls.size, calls.size), style = MaterialTheme.typography.labelSmall)
    }
    if (calls.size == 1 || expanded) calls.forEach { tool -> key(tool.id) { ToolRow(tool) } }
}

@Composable
private fun ToolRow(tool: ChatItem.ToolCall) {
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    val color = if (tool.state == ToolState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val verb = when (tool.verb) {
        "Ran" -> stringResource(R.string.tool_ran); "Read" -> stringResource(R.string.tool_read)
        "Edited" -> stringResource(R.string.tool_edited); "Wrote" -> stringResource(R.string.tool_wrote)
        "Searched" -> stringResource(R.string.tool_searched); "Fetched" -> stringResource(R.string.tool_fetched)
        else -> null
    }
    Column {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, stringResource(if (expanded) R.string.collapse else R.string.expand), Modifier.size(18.dp), tint = color)
            Text(verb ?: tool.name, modifier = if (tool.target.isNullOrBlank()) Modifier.weight(1f) else Modifier.widthIn(max = 100.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontFamily = if (verb == null) FontFamily.Monospace else null, color = color)
            tool.target?.let { Text(it, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = color) }
            if (tool.state == ToolState.FAILED) Text(stringResource(R.string.tool_failed), color = color, style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tool.name, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.tool_input), style = MaterialTheme.typography.labelSmall)
                SelectionContainer { Text(tool.inputSummary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                tool.result?.let {
                    Text(stringResource(R.string.tool_output), style = MaterialTheme.typography.labelSmall)
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}

@Composable
private fun Thought(item: ChatItem.Thinking) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(18.dp)); Text(stringResource(R.string.thinking), style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
