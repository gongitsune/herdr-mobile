package io.herdrmobile.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.herdrmobile.android.R
import io.herdrmobile.core.usage.AgentCommand
import io.herdrmobile.core.usage.CommandRun

@Composable
fun CommandSuggestionsPanel(query: CommandQuery?, commands: List<AgentCommand>?, enabled: Boolean, maxHeight: Dp, modifier: Modifier = Modifier, select: (AgentCommand) -> Unit) {
    if (query == null || maxHeight <= 0.dp) return
    val matches = remember(commands, query) { commands?.let { filterCommands(it, query) } }
    if (matches != null && matches.isEmpty()) return
    val title = stringResource(R.string.command_suggestions)
    Surface(modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 8.dp).semantics { paneTitle = title },
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        if (matches == null) Text(stringResource(R.string.command_loading), Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else key(query) {
            LazyColumn(Modifier.heightIn(max = maxHeight)) {
                items(matches, key = { "${it.trigger}${it.name}" }) { command ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { select(command) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${command.trigger}${command.name}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
                                if (command.argumentHint.isNotBlank()) Text(command.argumentHint, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            }
                            if (command.description.isNotBlank()) Text(command.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (command.run == CommandRun.TERMINAL) Icon(Icons.Default.Terminal, stringResource(R.string.command_terminal_only),
                            Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
