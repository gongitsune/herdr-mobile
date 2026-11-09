package io.herdrmobile.android.ui.hosts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.herdrmobile.android.R
import io.herdrmobile.core.ConnectionState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HostsScreen(vm: HostsViewModel) {
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val deleting by vm.deleting.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Button(onClick = { vm.edit(null) }) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.add_host)) } }
        if (hosts.isEmpty()) item { Text(stringResource(R.string.empty_hosts)) }
        itemsIndexed(hosts, key = { _, host -> host.id }) { index, host ->
            var menu by remember(host.id) { mutableStateOf(false) }
            SegmentedListItem(onClick = { vm.edit(host) }, shapes = ListItemDefaults.segmentedShapes(index, hosts.size),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                leadingContent = { Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) { Icon(Icons.Default.Dns, null, Modifier.padding(8.dp).size(20.dp)) } },
                supportingContent = { Text("${connectionText(statuses.find { it.host.id == host.id }?.state)} · ${host.user}@${host.host}:${host.port}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.host_actions)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.reconnect_now)) }, onClick = { menu = false; vm.reconnect(host.id) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.edit_host)) }, onClick = { menu = false; vm.edit(host) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete_host)) }, onClick = { menu = false; vm.error.value = null; vm.deleting.value = host })
                    }
                } }
            ) { Text(host.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    draft?.let { HostEditor(vm, it) }
    deleting?.let { host ->
        AlertDialog(onDismissRequest = { if (!busy) vm.deleting.value = null }, title = { Text(stringResource(R.string.delete_host)) },
            text = { Column { Text(stringResource(R.string.confirm_delete, host.name)); error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(enabled = !busy, onClick = vm::delete) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { vm.deleting.value = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
fun connectionText(state: ConnectionState?): String = when (state) {
    is ConnectionState.Connected -> stringResource(R.string.connected)
    ConnectionState.Connecting -> stringResource(R.string.connecting)
    is ConnectionState.Reconnecting -> stringResource(R.string.reconnecting, state.attempt, (state.nextDelayMs + 999) / 1000)
    is ConnectionState.Failed -> stringResource(R.string.failed, stringResource(when (state.error.kind) {
        "auth" -> R.string.auth_error; "host_key" -> R.string.host_key_error; "not_running" -> R.string.not_running
        "unsupported" -> R.string.unsupported_error; else -> R.string.io_error
    }))
    else -> stringResource(R.string.disconnected)
}

@Composable
private fun HostEditor(vm: HostsViewModel, value: HostDraft) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardLabel = stringResource(R.string.key_auth)
    var copied by remember(value.authorizedKey) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = vm::dismiss,
        title = { Text(stringResource(if (value.original == null) R.string.add_host else R.string.edit_host)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value.name, { vm.update { d -> d.copy(name = it) } }, enabled = !busy, label = { Text(stringResource(R.string.display_name)) }, singleLine = true)
            OutlinedTextField(value.host, { vm.update { d -> d.copy(host = it) } }, enabled = !busy, label = { Text(stringResource(R.string.hostname)) }, singleLine = true)
            OutlinedTextField(value.port, { vm.update { d -> d.copy(port = it) } }, enabled = !busy, label = { Text(stringResource(R.string.port)) }, singleLine = true)
            OutlinedTextField(value.user, { vm.update { d -> d.copy(user = it) } }, enabled = !busy, label = { Text(stringResource(R.string.ssh_user)) }, singleLine = true)
            OutlinedTextField(value.session, { vm.update { d -> d.copy(session = it) } }, enabled = !busy, label = { Text(stringResource(R.string.core_session)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = value.authType == "password", enabled = !busy && !generating, onClick = { vm.update { it.copy(authType = "password") } }, label = { Text(stringResource(R.string.password)) })
                FilterChip(selected = value.authType == "key", enabled = !busy && !generating, onClick = { vm.update { it.copy(authType = "key") } }, label = { Text(stringResource(R.string.key_auth)) })
            }
            if (value.authType == "password") {
                OutlinedTextField(value.password, { vm.update { d -> d.copy(password = it) } }, enabled = !busy,
                    label = { Text(stringResource(if (value.original?.encryptedPassword?.isNotEmpty() == true) R.string.keep_password else R.string.password)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
            } else {
                Button(enabled = !busy && !generating, onClick = vm::generate) { Text(stringResource(if (generating) R.string.generating else R.string.generate_key)) }
                if (value.authorizedKey.isNotBlank()) {
                    Text(stringResource(R.string.public_key_help))
                    SelectionContainer { Text(value.authorizedKey, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(clipboardLabel, value.authorizedKey))
                        copied = true
                    }) { Icon(Icons.Default.ContentCopy, null); Text(stringResource(if (copied) R.string.copied else R.string.copy_key)) }
                }
            }
            Text(stringResource(R.string.secret_help), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = value.valid && !busy && !generating, onClick = vm::save) { Text(stringResource(if (busy) R.string.saving else R.string.save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = vm::dismiss) { Text(stringResource(R.string.cancel)) } })
}
