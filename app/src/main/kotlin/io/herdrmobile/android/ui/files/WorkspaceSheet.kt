package io.herdrmobile.android.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.terminalErrorMessage
import io.herdrmobile.android.ui.theme.statusColor
import io.herdrmobile.core.files.FileEntry

enum class WorkspacePanel { CHANGES, FILES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSheet(vm: WorkspaceFilesViewModel, initial: WorkspacePanel, dismiss: () -> Unit) {
    var panel by remember { mutableStateOf(initial) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val fileError by vm.error.collectAsStateWithLifecycle()
    val changesError by vm.changesError.collectAsStateWithLifecycle()
    val error = if (panel == WorkspacePanel.CHANGES) changesError else fileError
    val notRepository = panel == WorkspacePanel.CHANGES && error?.kind == "not_repository"
    DisposableEffect(vm) { onDispose { vm.cancelLoads() } }
    LaunchedEffect(panel) {
        if (panel == WorkspacePanel.CHANGES) vm.refreshChanges() else vm.loadFolder()
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.workspace_panel), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { if (panel == WorkspacePanel.CHANGES) vm.refreshChanges() else vm.refreshFiles() }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
            }
            Text(vm.cwd, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                WorkspacePanel.entries.forEachIndexed { i, value -> SegmentedButton(selected = panel == value, onClick = { panel = value }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                    Text(stringResource(if (value == WorkspacePanel.CHANGES) R.string.changes else R.string.files))
                } }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.takeUnless { notRepository }?.let { Text(fileErrorMessage(it), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
            if (notRepository) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.not_repository), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (panel == WorkspacePanel.CHANGES) ChangesContent(vm, Modifier.weight(1f)) { entry ->
                vm.open(FileEntry(entry.path.substringAfterLast('/'), entry.path, false)); panel = WorkspacePanel.FILES
            } else FilesContent(vm, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChangesContent(vm: WorkspaceFilesViewModel, modifier: Modifier, openFile: (io.herdrmobile.core.files.ChangeEntry) -> Unit) {
    val changes by vm.changes.collectAsStateWithLifecycle()
    val staged by vm.staged.collectAsStateWithLifecycle()
    val rows by vm.diff.collectAsStateWithLifecycle()
    val added = statusColor("done")
    val removed = statusColor("error")
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (changes?.entries?.isEmpty() == true) item { Text(stringResource(R.string.no_changes), Modifier.padding(16.dp)) }
        items(changes?.entries.orEmpty(), key = { it.path }) { change ->
            Row(Modifier.fillMaxWidth().clickable { openFile(change) }.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (change.untracked) "??" else "${change.indexStatus}${change.worktreeStatus}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                Text(change.oldPath?.let { "$it → ${change.path}" } ?: change.path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Icon(Icons.Default.Description, stringResource(R.string.open_file), Modifier.size(18.dp))
            }
        }
        item { Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !staged, onClick = { vm.staged.value = false }, label = { Text(stringResource(R.string.worktree)) })
            FilterChip(selected = staged, onClick = { vm.staged.value = true }, label = { Text(stringResource(R.string.staged)) })
        } }
        if (changes?.truncated == true) item { Text(stringResource(R.string.diff_truncated), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (changes != null && rows.all { it.text.isBlank() }) item { Text(stringResource(R.string.no_diff), Modifier.padding(16.dp)) }
        itemsIndexed(rows) { _, row ->
            val color = when (row.kind) { DiffKind.ADD -> added; DiffKind.REMOVE -> removed; DiffKind.HUNK -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurface }
            val background = if (row.kind == DiffKind.ADD || row.kind == DiffKind.REMOVE) color.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
            Row(Modifier.fillMaxWidth().background(background).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("${row.oldLine ?: ""} ${row.newLine ?: ""}", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer(Modifier.weight(1f)) { Text(row.text, color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun FilesContent(vm: WorkspaceFilesViewModel, modifier: Modifier) {
    val tree by vm.tree.collectAsStateWithLifecycle()
    val expanded by vm.expanded.collectAsStateWithLifecycle()
    val loading by vm.loadingDirectories.collectAsStateWithLifecycle()
    val selected by vm.selectedFile.collectAsStateWithLifecycle()
    val file by vm.file.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth()) {
        selected?.let { entry ->
            Row {
                IconButton(onClick = vm::backToTree) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(entry.path, Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.file_read_only), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            val lines = remember(file) { file?.text?.split('\n').orEmpty() }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                itemsIndexed(lines) { i, line -> Row {
                    Text(((file?.first ?: 1) + i).toString(), Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer(Modifier.weight(1f)) { Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                } }
                if (file?.let { it.last < it.lines && it.last >= it.first } == true) item {
                    if (file!!.text.length < 1_000_000) TextButton(enabled = !busy, onClick = vm::more) { Text(stringResource(R.string.load_more)) }
                    else Text(stringResource(R.string.file_preview_limit))
                }
            }
        } ?: LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
            if (loading.isNotEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(tree, key = { it.entry.path }) { row ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !row.entry.directory || !row.entry.symlink) { if (row.entry.directory) vm.toggle(row.entry) else vm.open(row.entry) }
                    .padding(start = (row.depth * 16).dp, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(when { row.entry.symlink -> Icons.Default.Link; row.entry.directory && row.entry.path in expanded -> Icons.Default.FolderOpen; row.entry.directory -> Icons.Default.Folder; else -> Icons.Default.Description }, null, Modifier.size(20.dp))
                    Text(row.entry.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (tree.size >= 5000) item { Text(stringResource(R.string.file_preview_limit)) }
            if (tree.isEmpty() && loading.isEmpty()) item { Text(stringResource(R.string.files_empty)) }
        }
    }
}
