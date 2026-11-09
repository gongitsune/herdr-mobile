package io.herdrmobile.android.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.herdrmobile.android.R
import io.herdrmobile.core.workspace.AgentKind

@Composable
fun ProgramAvatar(kind: AgentKind?) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Icon(when (kind) { AgentKind.CLAUDE_CODE -> Icons.Default.AutoAwesome; AgentKind.CODEX -> Icons.Default.Code; null -> Icons.Default.Terminal; else -> Icons.Default.SmartToy }, null, Modifier.padding(8.dp).size(20.dp))
    }
}

@Composable
fun agentStateSentence(status: String?, title: String): String = stringResource(when (status) {
    "working" -> R.string.agent_working_on; "blocked", "error" -> R.string.agent_needs_you
    "done" -> R.string.agent_finished; else -> R.string.agent_standing_by
}, title)
