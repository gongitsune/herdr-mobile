package io.herdrmobile.android.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.theme.statusColor
import io.herdrmobile.core.usage.LimitWindow
import io.herdrmobile.core.workspace.AgentDetector
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.coroutines.delay
import java.text.NumberFormat
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
private fun limitLabel(window: LimitWindow): String = when (windowLabel(window.windowMinutes)) {
    WindowLabel.SESSION -> stringResource(R.string.usage_session)
    WindowLabel.WEEK -> stringResource(R.string.usage_week)
    WindowLabel.HOURS -> stringResource(R.string.usage_hours, NumberFormat.getNumberInstance().format(window.windowMinutes!! / 60.0))
    WindowLabel.UNKNOWN -> stringResource(R.string.usage_limit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionControls(vm: TerminalViewModel) {
    val record by vm.record.collectAsStateWithLifecycle()
    val kind = record?.let { AgentDetector.detect(it)?.kind }
    if (kind !in setOf(AgentKind.CLAUDE_CODE, AgentKind.CODEX)) return
    val session by vm.session.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val pending by vm.pendingModel.collectAsStateWithLifecycle()
    val confirmation by vm.modelConfirmation.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    // Read these states so availability updates while a sheet is open.
    val ready by vm.ready.collectAsStateWithLifecycle()
    val observe by vm.observe.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val items by vm.chatItems.collectAsStateWithLifecycle()
    val canSwitch = connected && ready && !observe && !busy && record?.status?.state != "working" &&
        activity?.running != true && !blocked && confirmation == null && io.herdrmobile.android.ui.chat.activeQuestion(items, blocked) == null
    var sheet by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sheet) {
        if (sheet != null) vm.refreshAccount()
        while (true) { now = System.currentTimeMillis(); delay(30_000) }
    }
    val models = account?.models.orEmpty()
    val current = currentModel(models, session?.model)
    val selected = selectedModel(models, session?.model, pending, session?.switchedTo)
    val label = displayedModel(models, session?.model, pending, session?.switchedTo) ?: stringResource(R.string.model)
    val limits = if (kind == AgentKind.CLAUDE_CODE) account?.limits else session?.limits
    val window = limits?.session(now)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { sheet = "model" }, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.weight(1f, fill = false).widthIn(max = 160.dp).heightIn(min = 40.dp)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                fontFamily = if (pending == null && session?.switchedTo == null && current == null && session?.model?.let { !knownModelLabel(it) } == true) FontFamily.Monospace else FontFamily.Default)
            Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp))
        }
        Spacer(Modifier.weight(1f))
        session?.context?.let { UsageNumber(stringResource(R.string.usage_context), it.usedPercent) { sheet = "usage" } }
        window?.let { UsageNumber(limitLabel(it), it.usedPercent.roundToInt(), showLabel = true) { sheet = "usage" } }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text(stringResource(if (sheet == "model") R.string.model else R.string.usage_title), style = MaterialTheme.typography.titleLarge)
            if (sheet == "model") {
                if (kind == AgentKind.CLAUDE_CODE) Text(stringResource(R.string.model_default_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!canSwitch) Text(stringResource(R.string.model_switch_disabled), style = MaterialTheme.typography.bodySmall)
                if (kind == AgentKind.CLAUDE_CODE && models.isNotEmpty()) {
                    models.forEach { model ->
                        ListItem(modifier = Modifier.clickable(enabled = canSwitch) { vm.switchModel(model); sheet = null },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            headlineContent = { Text(model.displayName, fontWeight = FontWeight.Bold, color = if (canSwitch) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)) },
                            supportingContent = model.description?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                            trailingContent = { if (model == selected) Icon(Icons.Default.Check, stringResource(R.string.model_current)) })
                    }
                } else {
                    Text(if (kind == AgentKind.CODEX) label else stringResource(when (account?.limitsUnavailable) {
                        "program_missing" -> R.string.model_program_missing
                        "program_too_old" -> R.string.model_program_old
                        else -> R.string.model_unavailable
                    }), Modifier.padding(vertical = 16.dp))
                    TextButton(enabled = canSwitch, onClick = { vm.switchModel(); sheet = null }) {
                        Text(stringResource(if (kind == AgentKind.CODEX) R.string.model_choose_terminal else R.string.model_terminal))
                    }
                }
            } else {
                session?.context?.let {
                    Text(stringResource(R.string.usage_tokens, compactTokens(it.usedTokens), compactTokens(it.windowTokens), it.usedPercent), Modifier.padding(vertical = 12.dp))
                }
                limits?.plan?.let { Text(stringResource(R.string.usage_plan, it)) }
                limits?.windows?.forEach { limit ->
                    val remaining = limit.resetsAtEpochMs?.let { ceil((it - now).coerceAtLeast(0) / 60_000.0).toLong() }
                    Text(limitLabel(limit) + when (limit.id) { "seven_day_opus" -> " · Opus"; "seven_day_sonnet" -> " · Sonnet"; else -> "" }, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    Text(stringResource(R.string.usage_used, limit.usedPercent.roundToInt()))
                    remaining?.let { Text(stringResource(R.string.usage_reset, it / 60, it % 60), style = MaterialTheme.typography.bodySmall) }
                }
                if (limits == null) Text(stringResource(when (account?.limitsUnavailable) {
                    "no_plan_limits" -> R.string.usage_no_plan
                    "program_missing" -> R.string.model_program_missing
                    "program_too_old" -> R.string.model_program_old
                    else -> R.string.usage_unavailable
                }))
                if (kind == AgentKind.CLAUDE_CODE) TextButton(onClick = { vm.refreshAccount(true) }, enabled = connected) { Text(stringResource(R.string.usage_refresh)) }
            }
        }
    }
}

internal fun compactTokens(tokens: Long): String = if (tokens >= 1000) NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(tokens / 1000.0) + "k" else tokens.toString()

@Composable
private fun UsageNumber(label: String, percent: Int, showLabel: Boolean = false, click: () -> Unit) {
    val color = if (percent >= 80) statusColor("blocked") else MaterialTheme.colorScheme.onSurfaceVariant
    val description = stringResource(R.string.usage_percent, label, percent)
    TextButton(onClick = click, modifier = Modifier.semantics { contentDescription = description }, contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.textButtonColors(contentColor = color)) {
        CircularProgressIndicator(progress = { percent.coerceIn(0, 100) / 100f }, modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = color)
        Spacer(Modifier.width(4.dp))
        Text(if (showLabel) description else stringResource(R.string.usage_number, percent), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
