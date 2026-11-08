package io.herdrmobile.android.ui.console

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.herdrmobile.android.R
import io.herdrmobile.core.ConnectionState

@Composable
fun ConsoleScreen(vm: ConsoleViewModel, openTerminal: (String, String) -> Unit) {
    val terminals by vm.terminals.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.console_intro), style = MaterialTheme.typography.headlineSmall) }
            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == null, onClick = { vm.filter.value = null }, label = { Text(stringResource(R.string.all_hosts)) })
                hosts.forEach { host -> FilterChip(selected = filter == host.host.id, onClick = { vm.filter.value = host.host.id }, label = { Text(host.host.name) }) }
            } }
            val visible = terminals.filter { filter == null || it.hostId == filter }
            if (visible.isEmpty()) item { Text(stringResource(R.string.console_empty)) }
            items(visible, key = { "${it.hostId}:${it.terminal.id}" }) { row ->
                Card(Modifier.fillMaxWidth().alpha(if (row.stale) 0.45f else 1f).clickable(enabled = !row.stale) { openTerminal(row.hostId, row.terminal.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (row.stale) Text(stringResource(R.string.stale_terminals), style = MaterialTheme.typography.labelSmall)
                        StatusChip(row.terminal.status?.state ?: row.terminal.state)
                        Text(row.terminal.title.ifBlank { row.terminal.name ?: row.terminal.foreground?.name.orEmpty() }, style = MaterialTheme.typography.titleMedium)
                        Text(row.terminal.cwd, style = MaterialTheme.typography.bodySmall)
                        Text(row.hostName, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        FloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp)) {
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
    val (label, color) = when (status) {
        "blocked" -> R.string.blocked to Color(0xFFFFCC80)
        "error" -> R.string.error to Color(0xFFFF9C9C)
        "working" -> R.string.working to Color(0xFF90CAF9)
        "done" -> R.string.done to Color(0xFF99D7BB)
        "idle" -> R.string.idle to MaterialTheme.colorScheme.onSurfaceVariant
        "finished" -> R.string.finished to MaterialTheme.colorScheme.onSurfaceVariant
        "running" -> R.string.running to MaterialTheme.colorScheme.onSurfaceVariant
        else -> R.string.unknown_status to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.15f), contentColor = color, shape = MaterialTheme.shapes.small) {
        Text(stringResource(label), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}
