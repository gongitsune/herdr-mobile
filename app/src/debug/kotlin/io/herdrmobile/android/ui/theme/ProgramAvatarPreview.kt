package io.herdrmobile.android.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.herdrmobile.core.workspace.AgentKind

@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProgramAvatarPreview() {
    HerdrTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(AgentKind.CLAUDE_CODE, AgentKind.CODEX, null, AgentKind.OTHER).forEach { kind ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProgramAvatar(kind)
                        ProgramIcon(kind, Modifier.size(24.dp))
                        Text(kind?.name ?: "Shell")
                    }
                }
            }
        }
    }
}
