package io.herdrmobile.android.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.herdrmobile.android.R
import io.herdrmobile.core.workspace.AgentKind

@Composable
fun ProgramAvatar(kind: AgentKind?) {
    if (kind == AgentKind.CLAUDE_CODE) {
        ProgramIcon(kind, Modifier.size(36.dp))
    } else {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
            ProgramIcon(kind, Modifier.padding(8.dp).size(20.dp))
        }
    }
}

/** Decorative program mark; the surrounding row supplies its accessible label. */
@Composable
internal fun ProgramIcon(kind: AgentKind?, modifier: Modifier = Modifier) {
    when (kind) {
        AgentKind.CLAUDE_CODE -> Image(painterResource(R.drawable.ic_agent_claude), null, modifier)
        AgentKind.CODEX -> Icon(painterResource(R.drawable.ic_agent_codex), null, modifier, tint = LocalContentColor.current)
        else -> Icon(if (kind == null) Icons.Default.Terminal else Icons.Default.SmartToy, null, modifier)
    }
}
