package io.herdrmobile.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.theme.ProgramAvatar
import io.herdrmobile.android.ui.theme.statusColor
import io.herdrmobile.core.workspace.*

@Composable
internal fun WorkspaceTitle(title: String, space: String, kind: AgentKind?, enabled: Boolean, open: () -> Unit) {
    Row(Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, onClick = open), verticalAlignment = Alignment.CenterVertically) {
        ProgramAvatar(kind)
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            if (space.isNotEmpty()) Text(space, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (enabled) Icon(Icons.Default.ExpandMore, stringResource(R.string.show_tabs))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceTabsSheet(space: Space, terminal: String?, dismiss: () -> Unit, open: (Tab) -> Unit, close: (Tab) -> Unit, shell: () -> Unit, agent: () -> Unit, canRename: Boolean, rename: (Tab) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.space_tabs, space.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp))
            space.tabs.forEach { tab ->
                val selected = tab.containsTerminal(terminal)
                Row(Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.small), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).combinedClickable(onClick = { open(tab) }, onLongClickLabel = stringResource(R.string.rename), onLongClick = if (canRename) ({ rename(tab) }) else null).padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val state = tab.aggregateState()
                        val color = if (state in listOf("blocked", "error", "working", "done")) statusColor(state!!) else MaterialTheme.colorScheme.onSurfaceVariant
                        Canvas(Modifier.size(8.dp)) {
                            if (state in listOf("blocked", "error", "working", "done")) drawCircle(color) else drawCircle(color, style = Stroke(1.5.dp.toPx()))
                        }
                        ProgramAvatar(tab.openingPane()?.agent?.kind)
                        Text(tab.displayTitle(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val word = when (state) { "blocked", "error" -> R.string.tab_blocked; "working" -> R.string.tab_working; "done" -> R.string.tab_done; else -> null }
                        word?.let { Text(stringResource(it), color = color, style = MaterialTheme.typography.labelSmall) }
                    }
                    IconButton(onClick = { close(tab) }) { Icon(Icons.Default.Close, stringResource(R.string.close_named_tab, tab.displayTitle())) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            space.tabs.find { it.containsTerminal(terminal) }?.let { tab ->
                SheetAction(Icons.Default.Edit, stringResource(R.string.rename), canRename) { rename(tab) }
            }
            CreationActions(shell, agent)
            TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpaceActionsSheet(space: Space, branchesForSpace: String?, dismiss: () -> Unit, files: () -> Unit, shell: () -> Unit, agent: () -> Unit, canRename: Boolean, rename: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(space.name, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(branchesForSpace, space.cwd.takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SheetAction(Icons.Default.Edit, stringResource(R.string.rename), canRename, rename)
            SheetAction(Icons.Default.FolderOpen, stringResource(R.string.workspace_panel), action = files)
            CreationActions(shell, agent)
            TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun CreationActions(shell: () -> Unit, agent: () -> Unit) {
    SheetAction(Icons.Default.Add, stringResource(R.string.new_terminal), action = shell)
    SheetAction(Icons.Default.SmartToy, stringResource(R.string.new_agent_tab), action = agent)
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, enabled: Boolean = true, action: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = action)
            .then(if (enabled) Modifier else Modifier.alpha(0.38f)),
    )
}
