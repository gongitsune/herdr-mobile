package io.herdrmobile.android.ui.chat

import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.theme.LocalHerdrDark
import io.herdrmobile.android.ui.terminal.LiveTerminalPreview
import io.herdrmobile.android.ui.terminal.TerminalViewModel
import io.herdrmobile.core.transcript.TurnActivity
import kotlinx.coroutines.delay

data class LiveHeader(val working: Boolean, val elapsed: String?, val lastStep: String?)
private data class LiveInput(val working: Boolean, val blocked: Boolean, val activity: TurnActivity?, val turn: ChatTurn?, val revision: String?, val statusAtEpochMs: Long?, val connected: Boolean)

@Composable
fun rememberLiveHeader(working: Boolean, blocked: Boolean, activity: TurnActivity?, items: List<io.herdrmobile.core.transcript.ChatItem>, statusAtEpochMs: Long? = null, connected: Boolean = true): LiveHeader? {
    val turns = remember(items, working) { projectTurns(items, working) }
    val turn = (turns.lastOrNull { it is TimelineEntry.Turn } as? TimelineEntry.Turn)?.value
    val latest by rememberUpdatedState(LiveInput(working, blocked, activity, turn, items.lastOrNull()?.let { "${it.id}:${it.hashCode()}" }, statusAtEpochMs, connected))
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val header by produceState<LiveHeader?>(null, lifecycle) {
        val gate = ActivityVisibility()
        var previousPrompt: String? = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val input = latest
                if (!input.connected) {
                    gate.update(SystemClock.elapsedRealtime(), false, false, true, "disconnected", true)
                    value = null; delay(100); continue
                }
                val turn = input.turn
                val answer = turn?.answers?.isNotEmpty() == true && (turn.recordEnded || !input.working)
                val newPrompt = turn?.prompt?.id != null && turn.prompt.id != previousPrompt
                previousPrompt = turn?.prompt?.id
                val show = gate.update(SystemClock.elapsedRealtime(), input.working, input.blocked, answer,
                    input.revision, newPrompt)
                value = when {
                    input.blocked -> LiveHeader(false, null, null)
                    show -> LiveHeader(true, elapsedLabel(if (turn?.recordEnded == true) input.statusAtEpochMs else input.activity?.startedAtEpochMs, System.currentTimeMillis()), input.activity?.lastStep)
                    else -> null
                }
                delay(100)
            }
        }
    }
    return header
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LiveActivity(vm: TerminalViewModel, header: LiveHeader, peek: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (header.working) {
                val animation = rememberInfiniteTransition(label = "working")
                val pulse by animation.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "activity dot")
                Box(Modifier.size(7.dp).alpha(pulse).background(MaterialTheme.colorScheme.primary, CircleShape))
                LoadingIndicator(Modifier.size(24.dp))
            } else Box(Modifier.size(7.dp).background(if (LocalHerdrDark.current) Color(0xFFE5B950) else Color(0xFF936700), CircleShape))
            Text(if (header.working) header.elapsed?.let { stringResource(R.string.working_elapsed, it) } ?: stringResource(R.string.working_ellipsis) else stringResource(R.string.needs_you), style = MaterialTheme.typography.labelMedium)
            header.lastStep?.let { step -> Text(when (step) { "Thinking" -> stringResource(R.string.thinking); "Writing" -> stringResource(R.string.writing); else -> step }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (header.working) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    LiveTerminalPreview(vm, Modifier.fillMaxWidth().height(104.dp), tail = true, onTap = peek)
                    TextButton(onClick = peek, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 40.dp)) {
                        Icon(Icons.Default.Terminal, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.live_terminal), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
