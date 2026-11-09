package io.herdrmobile.android.ui.console

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import io.herdrmobile.android.connection.ConsoleTerminal
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import io.herdrmobile.android.ui.theme.ProgramAvatar
import io.herdrmobile.android.ui.theme.agentStateSentence
import io.herdrmobile.core.workspace.AgentDetector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.theme.statusColor
import io.herdrmobile.core.ConnectionState

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConsoleScreen(vm: ConsoleViewModel, peek: (ConsoleTerminal) -> Unit, openTerminal: (String, String) -> Unit) {
    val terminals by vm.terminals.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == null, onClick = { vm.filter.value = null }, label = { Text(stringResource(R.string.all_hosts)) })
                hosts.forEach { host -> FilterChip(selected = filter == host.host.id, onClick = { vm.filter.value = host.host.id }, label = { Text(host.host.name) }) }
            } }
            val visible = terminals.filter { filter == null || it.hostId == filter }
            if (visible.isEmpty()) item { Text(stringResource(R.string.console_empty)) }
            groupAgents(visible).forEach { (group, groupRows) ->
                if (groupRows.isNotEmpty()) {
                    item(key = "group-${group.name}") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(when (group) { AgentGroup.BLOCKED -> R.string.blocked; AgentGroup.WORKING -> R.string.working; AgentGroup.DONE -> R.string.done; AgentGroup.IDLE -> R.string.idle }), style = MaterialTheme.typography.titleSmall)
                            Text(groupRows.size.toString(), style = MaterialTheme.typography.labelLarge)
                        }
                    }
            itemsIndexed(groupRows, key = { _, row -> "${row.hostId}:${row.terminal.id}" }) { index, row ->
                val title = row.terminal.title.ifBlank { row.terminal.name ?: row.terminal.foreground?.name.orEmpty() }
                SegmentedListItem(onClick = { openTerminal(row.hostId, row.terminal.id) }, onLongClick = { peek(row) },
                    shapes = ListItemDefaults.segmentedShapes(index, groupRows.size), enabled = !row.stale,
                    modifier = Modifier.fillMaxWidth().alpha(if (row.stale) 0.45f else 1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    leadingContent = { ProgramAvatar(AgentDetector.detect(row.terminal)?.kind) },
                    supportingContent = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (row.stale) stringResource(R.string.stale_terminals) else agentStateSentence(row.terminal.status?.state, title), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.hostName, Modifier.widthIn(max = 96.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    } },
                    trailingContent = { Box(Modifier.size(8.dp).background(statusColor(row.terminal.status?.state.orEmpty()), CircleShape)) }
                ) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
                }
            }
        }
        MediumFloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, stringResource(R.string.new_terminal))
        }
    }
    if (creating) {
        val connected = hosts.filter { it.state is ConnectionState.Connected }
        var hostId by rememberSaveable { mutableStateOf(connected.firstOrNull()?.host?.id.orEmpty()) }
        var command by rememberSaveable { mutableStateOf("") }
        var cwd by rememberSaveable { mutableStateOf("") }
        val busy by vm.creating.collectAsStateWithLifecycle()
        val error by vm.error.collectAsStateWithLifecycle()
        AlertDialog(onDismissRequest = { if (!busy) creating = false }, title = { Text(stringResource(R.string.new_terminal)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.choose_host))
                connected.forEach { host -> FilterChip(selected = hostId == host.host.id, enabled = !busy, onClick = { hostId = host.host.id }, label = { Text(host.host.name) }) }
                OutlinedTextField(command, { command = it }, enabled = !busy, label = { Text(stringResource(R.string.command)) })
                OutlinedTextField(cwd, { cwd = it }, enabled = !busy, label = { Text(stringResource(R.string.cwd)) })
                if (error) Text(stringResource(R.string.create_error), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = command.isNotBlank() && connected.any { it.host.id == hostId } && !busy,
                onClick = { vm.create(hostId, command, cwd) { id -> creating = false; openTerminal(hostId, id) } }) { Text(stringResource(R.string.create)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { creating = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
fun StatusChip(status: String) {
    val color = statusColor(status)
    val label = when (status) {
        "blocked" -> R.string.blocked; "error" -> R.string.error; "working" -> R.string.working
        "done" -> R.string.done; "idle" -> R.string.idle; "finished" -> R.string.finished
        "running" -> R.string.running; else -> R.string.unknown_status
    }
    Surface(color = color.copy(alpha = 0.15f), contentColor = color, shape = MaterialTheme.shapes.small) {
        Text(stringResource(label), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}
