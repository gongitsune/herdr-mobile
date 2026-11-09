package io.herdrmobile.android.ui.terminal

import io.herdrmobile.core.model.Foreground
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.rpc.HcError
import io.herdrmobile.core.transcript.TranscriptBinding
import io.herdrmobile.core.transcript.TranscriptState
import io.herdrmobile.core.workspace.AgentKind
import org.junit.Assert.*
import org.junit.Test

class DetailPresentationTest {
    private val codex = Terminal(address = "codex", pid = 10, argv = listOf("codex"),
        foreground = Foreground(pid = 20, argv = listOf("codex")))
    private val ready = TranscriptState.Ready(TranscriptBinding(AgentKind.CODEX, "session", "/log", "process"), true)
    private val unavailable = TranscriptState.Unavailable("No log")
    private val failed = TranscriptState.Failed(HcError("io", "Read failed"))

    @Test fun unknownRecordShowsNeitherSurfaceUntilClassificationArrives() {
        val unknown = DetailPresentation()
        assertFalse(unknown.known)
        assertNull(unknown.surface)
        assertEquals(unknown, unknown.withTranscript(TranscriptState.Loading))
        assertEquals(unknown, unknown.withTranscript(ready))
        assertEquals(unknown, unknown.select(DetailSurface.TERMINAL))
        assertEquals(DetailSurface.CHAT, unknown.withTerminal(codex).surface)
    }

    @Test fun supportedAgentsStayOnChatThroughoutLoadingWithoutItems() {
        for (program in listOf("codex", "claude")) {
            val terminal = codex.copy(foreground = Foreground(pid = 20, argv = listOf(program)))
            val state = DetailPresentation().withTerminal(terminal)
            assertTrue(state.known)
            assertEquals(DetailSurface.CHAT, state.surface)
            assertEquals(state, state.withTranscript(null))
            assertEquals(state, state.withTranscript(TranscriptState.Loading))
        }
    }

    @Test fun knownShellAndUnsupportedAgentImmediatelyShowTerminal() {
        for (program in listOf("bash", "gemini")) {
            val terminal = codex.copy(foreground = Foreground(argv = listOf(program)))
            val state = DetailPresentation().withTerminal(terminal)
            assertTrue(state.known)
            assertEquals(DetailSurface.TERMINAL, state.surface)
            assertEquals(state, state.select(DetailSurface.CHAT).withTranscript(ready))
        }
    }

    @Test fun firstCompletedLookupChoosesSurfaceExactlyOnce() {
        val loading = DetailPresentation().withTerminal(codex).withTranscript(TranscriptState.Loading)
        for (result in listOf(ready, TranscriptState.NotStarted(AgentKind.CODEX), unavailable, failed)) {
            val resolved = loading.withTranscript(result)
            assertTrue(resolved.automaticResolved)
            assertEquals(if (result == unavailable || result == failed) DetailSurface.TERMINAL else DetailSurface.CHAT, resolved.surface)
            for (later in listOf(null, TranscriptState.Loading, ready, unavailable, failed)) {
                assertEquals(resolved, resolved.withTranscript(later))
            }
        }
    }

    @Test fun userChoiceDuringLoadingAlwaysWinsIncludingFailureOnChat() {
        for (surface in DetailSurface.entries) {
            val selected = DetailPresentation().withTerminal(codex).select(surface)
            for (state in listOf(null, TranscriptState.Loading, ready, unavailable, failed)) {
                assertEquals(surface, selected.withTranscript(state).surface)
            }
        }
    }

    @Test fun userCanSwitchAgainAfterAutomaticResolution() {
        val state = DetailPresentation().withTerminal(codex).withTranscript(unavailable).select(DetailSurface.CHAT)
        assertEquals(DetailSurface.CHAT, state.withTranscript(failed).surface)
        assertEquals(DetailSurface.TERMINAL, state.select(DetailSurface.TERMINAL).withTranscript(ready).surface)
    }

    @Test fun reopeningUsesCachedKindAndSurfaceBeforeRecordOrTranscriptArrives() {
        for (surface in DetailSurface.entries) {
            val cached = DetailPresentation().withTerminal(codex).select(surface)
            val reopened = cached.reopened()
            assertTrue(reopened.known)
            assertEquals(AgentKind.CODEX, reopened.kind)
            assertEquals(surface, reopened.surface)
            assertEquals(surface, reopened.withTranscript(TranscriptState.Loading).surface)
            assertEquals(surface, reopened.withTerminal(codex).withTranscript(ready).surface)
        }
        val shell = codex.copy(foreground = Foreground(argv = listOf("bash")))
        val cachedShell = DetailPresentation().withTerminal(shell).reopened()
        assertTrue(cachedShell.known)
        assertNull(cachedShell.kind)
        assertEquals(DetailSurface.TERMINAL, cachedShell.surface)
    }

    @Test fun reopeningCanResolveAgainAfterPreviousAutomaticFailure() {
        val cached = DetailPresentation().withTerminal(codex).withTranscript(failed)
        val reopened = cached.reopened().withTerminal(codex)
        assertEquals(DetailSurface.TERMINAL, reopened.withTranscript(TranscriptState.Loading).surface)
        assertEquals(DetailSurface.CHAT, reopened.withTranscript(ready).surface)
    }

    @Test fun newProcessResetsAutomaticResolutionButDoesNotOverrideUserChoice() {
        val next = codex.copy(foreground = codex.foreground!!.copy(pid = 21))
        val resolved = DetailPresentation().withTerminal(codex).withTranscript(failed)
        assertEquals(resolved, resolved.withTerminal(codex.copy(title = "Updated title")))
        val restarted = resolved.withTerminal(next)
        assertFalse(restarted.automaticResolved)
        assertEquals(DetailSurface.CHAT, restarted.withTranscript(ready).surface)
        val selected = resolved.select(DetailSurface.TERMINAL).withTerminal(next)
        assertEquals(DetailSurface.TERMINAL, selected.withTranscript(ready).surface)
        val shell = next.copy(foreground = Foreground(argv = listOf("bash")))
        assertEquals(DetailSurface.TERMINAL, restarted.select(DetailSurface.CHAT).withTerminal(shell).surface)
    }
}
