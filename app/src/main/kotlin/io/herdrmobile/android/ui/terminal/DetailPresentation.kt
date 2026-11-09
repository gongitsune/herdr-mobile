package io.herdrmobile.android.ui.terminal

import io.herdrmobile.android.ui.chat.supportsChat
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.transcript.TranscriptState
import io.herdrmobile.core.workspace.AgentDetector
import io.herdrmobile.core.workspace.AgentKind

/** Null surface means that no record (or cached classification) has arrived yet. */
data class DetailPresentation(
    val known: Boolean = false,
    val kind: AgentKind? = null,
    val pid: Long? = null,
    val surface: DetailSurface? = null,
    val automaticResolved: Boolean = false,
    val manuallySelected: Boolean = false,
) {
    fun withTerminal(terminal: Terminal): DetailPresentation {
        val nextKind = AgentDetector.detect(terminal)?.kind
        val nextPid = terminal.foreground?.pid?.toLong() ?: terminal.pid
        if (known && kind == nextKind && pid == nextPid) return this
        val nextSurface = when {
            !supportsChat(nextKind) -> DetailSurface.TERMINAL
            manuallySelected -> surface
            else -> DetailSurface.CHAT
        }
        return copy(known = true, kind = nextKind, pid = nextPid, surface = nextSurface,
            automaticResolved = false)
    }

    /** Only the first completed lookup may choose a surface automatically. */
    fun withTranscript(state: TranscriptState?): DetailPresentation {
        if (!supportsChat(kind) || manuallySelected || automaticResolved) return this
        val nextSurface = when (state) {
            is TranscriptState.Ready, is TranscriptState.NotStarted -> DetailSurface.CHAT
            is TranscriptState.Unavailable, is TranscriptState.Failed -> DetailSurface.TERMINAL
            else -> return this
        }
        return copy(surface = nextSurface, automaticResolved = true)
    }

    fun select(value: DetailSurface): DetailPresentation =
        if (supportsChat(kind)) copy(surface = value, manuallySelected = true) else this

    /** Retain the last surface while a new lookup runs; keep explicit choices across tabs. */
    fun reopened(): DetailPresentation = copy(automaticResolved = false)
}

enum class DetailSurface { TERMINAL, CHAT }
