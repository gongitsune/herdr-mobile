package io.herdrmobile.android.ui.chat

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.herdrmobile.core.transcript.TurnActivity
import kotlinx.coroutines.delay

data class LiveHeader(val working: Boolean, val elapsed: String?)
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
                    input.blocked -> LiveHeader(false, null)
                    show -> LiveHeader(true, elapsedLabel(if (turn?.recordEnded == true) input.statusAtEpochMs else input.activity?.startedAtEpochMs, System.currentTimeMillis()))
                    else -> null
                }
                delay(100)
            }
        }
    }
    return header
}
