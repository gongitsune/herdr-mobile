package io.herdrmobile.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import io.herdrmobile.android.R

@Composable
fun ChatMarkdown(content: String) {
    Markdown(content, components = markdownComponents(
        codeFence = { model -> MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, _ -> CodeCard(code, language) } },
        codeBlock = { model -> MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, _ -> CodeCard(code, language) } },
    ))
}

@Composable
private fun CodeCard(code: String, language: String?) {
    val context = LocalContext.current
    val label = stringResource(R.string.copy_code)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.orEmpty(), Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, code)) }) { Icon(Icons.Default.ContentCopy, label, Modifier.size(18.dp)) }
            }
            HorizontalDivider()
            SelectionContainer { Text(code, Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
