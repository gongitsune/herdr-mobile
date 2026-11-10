package io.herdrmobile.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.herdrmobile.android.ui.theme.ProgramAvatar

@Composable
internal fun RelatedAgentsRow(links: List<WorkspaceAgentLink>, open: (String) -> Unit) {
    if (links.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        links.forEach { link ->
            TextButton(onClick = { open(link.terminalId) },
                modifier = Modifier.alpha(if (link.stale) 0.45f else 1f).semantics { selected = link.selected },
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (link.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    contentColor = if (link.selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                )) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProgramAvatar(link.kind, size = 20.dp)
                    Text(link.title, Modifier.widthIn(max = 200.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
