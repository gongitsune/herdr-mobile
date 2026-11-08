package io.herdrmobile.android.ui.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.herdrmobile.android.R
import io.herdrmobile.android.connection.ConsoleTerminal
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.android.ui.console.StatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeekSheet(connections: HostConnections, row: ConsoleTerminal, dismiss: () -> Unit, open: () -> Unit) {
    val rows by connections.terminals.collectAsStateWithLifecycle()
    val current = rows.find { it.hostId == row.hostId && it.terminal.id == row.terminal.id } ?: row
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(current.terminal.title.ifBlank { current.terminal.name ?: current.terminal.id }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                StatusChip(current.terminal.status?.state ?: "idle")
            }
            Text(stringResource(R.string.peek_read_only), Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall)
            ObservePreview(connections, row.hostId, row.terminal.id, Modifier.fillMaxWidth().height(320.dp))
            TextButton(onClick = { dismiss(); open() }, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.open_agent)) }
        }
    }
}
