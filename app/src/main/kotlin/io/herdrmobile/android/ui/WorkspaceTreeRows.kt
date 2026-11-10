package io.herdrmobile.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.theme.ProgramAvatar

@Composable
internal fun WorkspaceTreeRows(rows: List<WorkspaceTreeRow>, open: (WorkspaceTreeRow) -> Unit) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth()
            .height(if (row.kind == WorkspaceTreeKind.TAB) 40.dp else 36.dp)
            .drawBehind {
                repeat(row.depth) { depth ->
                    val x = (24 + depth * 16).dp.toPx()
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
            }
            .padding(start = (32 + row.depth * 16).dp)
            .background(if (row.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.small)
            .semantics { selected = row.selected }
            .clickable { open(row) }
            .alpha(if (row.stale) 0.45f else 1f)
            .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (row.kind == WorkspaceTreeKind.MORE) {
                Text(pluralStringResource(R.plurals.tree_more_agents, row.hiddenCount, row.hiddenCount),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ProgramAvatar(row.agentKind, size = 20.dp)
                Text(row.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (row.bold) FontWeight.Bold else FontWeight.Normal)
                AgentMark(row.state, row.unread)
            }
        }
    }
}
