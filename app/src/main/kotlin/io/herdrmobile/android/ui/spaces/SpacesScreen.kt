package io.herdrmobile.android.ui.spaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.android.ui.files.*
import io.herdrmobile.android.ui.factory
import io.herdrmobile.android.ui.terminal.ObservePreview
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.console.StatusChip

@Composable
fun SpacesScreen(vm: SpacesViewModel, openSpace: (String, String) -> Unit) {
    val spaces by vm.spaces.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.spaces), style = MaterialTheme.typography.headlineSmall)
            FilterChip(selected = demo, onClick = { vm.demo.value = !demo }, label = { Text(stringResource(R.string.demo_label)) })
        } }
        if (!demo) errors.forEach { error -> item { Text(stringResource(R.string.workspace_error_kind, error.kind), color = MaterialTheme.colorScheme.error) } }
        if (spaces.any { it.demo }) item { Text(stringResource(R.string.workspace_demo), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        spaces.groupBy { it.hostId }.forEach { (_, group) ->
            item { Text(group.first().hostName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(group, key = { "${it.hostId}:${it.id}" }) { space ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { openSpace(space.hostId, space.id) }) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(space.name.take(2).uppercase(), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(space.name, style = MaterialTheme.typography.titleMedium)
                            Text(space.folder, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            val paneCount = space.tabs.sumOf { it.panes.size }
                            Text("${pluralStringResource(R.plurals.space_tabs, space.tabs.size, space.tabs.size)} · ${pluralStringResource(R.plurals.space_panes, paneCount, paneCount)}", style = MaterialTheme.typography.labelSmall)
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
        }
        if (spaces.isEmpty()) item { Text(stringResource(R.string.spaces_empty)) }
    }
}

@Composable
fun SpaceDetailScreen(vm: SpacesViewModel, hostId: String, spaceId: String, connections: HostConnections, back: () -> Unit, openTerminal: (String, String) -> Unit) {
    val spaces by vm.spaces.collectAsStateWithLifecycle()
    val selected by vm.selectedTabs.collectAsStateWithLifecycle()
    val space = spaces.find { it.id == spaceId && it.hostId == hostId }
    if (space == null) { TextButton(onClick = back) { Text(stringResource(R.string.back)) }; return }
    val tab = space.tabs.find { it.id == selected["${space.hostId}:${space.id}"] } ?: space.tabs.firstOrNull()
    var panel by remember { mutableStateOf<WorkspacePanel?>(null) }
    var focused by rememberSaveable(hostId, spaceId, tab?.id) { mutableStateOf<String?>(null) }
    val activePane = tab?.panes?.find { it.id == focused } ?: tab?.panes?.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Column(Modifier.padding(8.dp)) { Text(space.name, style = MaterialTheme.typography.titleMedium); Text(space.hostName, style = MaterialTheme.typography.labelSmall) }
        }
        if (space.demo) Text(stringResource(R.string.workspace_demo), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        if (space.tabs.isNotEmpty()) ScrollableTabRow(selectedTabIndex = space.tabs.indexOf(tab).coerceAtLeast(0), edgePadding = 8.dp) {
            space.tabs.forEach { current -> Tab(selected = current.id == tab?.id, onClick = { vm.select("${space.hostId}:${space.id}", current.id) }, text = { Text(current.name) }) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tab?.panes.orEmpty(), key = { it.id }) { pane ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { focused = pane.id }, border = androidx.compose.foundation.BorderStroke(1.dp, if (pane.id == activePane?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(pane.title, style = MaterialTheme.typography.titleSmall); StatusChip(pane.status ?: "idle") }
                        Text(pane.cwd, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ObservePreview(connections, space.hostId, pane.terminalId, Modifier.fillMaxWidth().height(200.dp))
                        TextButton(onClick = { openTerminal(space.hostId, pane.terminalId) }) { Icon(Icons.Default.Terminal, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.open_agent)) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { panel = WorkspacePanel.CHANGES }) { Icon(Icons.Default.Difference, null); Text(stringResource(R.string.changes)) }
            OutlinedButton(onClick = { panel = WorkspacePanel.FILES }) { Icon(Icons.Default.FolderOpen, null); Text(stringResource(R.string.files)) }
        }
        HorizontalDivider()
        Text("${space.hostName} · ${activePane?.cwd ?: space.folder}", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    panel?.let { selectedPanel ->
        val cwd = activePane?.cwd?.ifBlank { space.folder } ?: space.folder
        val filesVm: WorkspaceFilesViewModel = viewModel(key = "files:${space.hostId}:${space.id}:$cwd:${space.machine}", factory = factory { WorkspaceFilesViewModel(connections, space.hostId, cwd, space.machine, space.demo) })
        WorkspaceSheet(filesVm, selectedPanel) { panel = null }
    }
}
