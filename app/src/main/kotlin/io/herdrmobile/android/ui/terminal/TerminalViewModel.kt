package io.herdrmobile.android.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.view.TerminalView
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.android.ui.asHcError
import io.herdrmobile.core.*
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.transcript.*
import io.herdrmobile.android.ui.chat.DemoConversation
import io.herdrmobile.android.ui.chat.canChooseOption
import io.herdrmobile.android.ui.chat.visibleChatItems
import io.herdrmobile.android.ui.theme.AtomPalette
import com.termux.terminal.TerminalColors
import io.herdrmobile.core.rpc.HcError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class TerminalViewModel(private val connections: HostConnections, private val hostId: String, private val id: String, initialObserve: Boolean = false) : ViewModel() {
    val demo = hostId == "demo"
    val observe = MutableStateFlow(initialObserve || demo)
    val chatItems = MutableStateFlow(if (demo) DemoConversation.items else emptyList<ChatItem>())
    val transcriptState = MutableStateFlow<TranscriptState?>(null)
    val blocked = MutableStateFlow(demo)
    val selectedSurface = MutableStateFlow<DetailSurface?>(null)
    val candidate = MutableStateFlow<TranscriptCandidate?>(null)
    val candidatePreview = MutableStateFlow<List<ChatItem>?>(null)
    val candidateError = MutableStateFlow<HcError?>(null)
    val candidateBusy = MutableStateFlow(false)
    private val previewOnly = initialObserve
    private var source: TranscriptSource? = null
    private var sourceClient: HcClient? = null
    private var sourceWatch: Job? = null
    private var previewJob: Job? = null
    val control = MutableStateFlow(false)
    val draft = MutableStateFlow("")
    val error = MutableStateFlow<HcError?>(null)
    val ended = MutableStateFlow(false)
    val recovering = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val text = MutableStateFlow<String?>(null)
    private var generation = 0
    private var remote: RemoteTerminalSession? = null
    private var watch: Job? = null

    fun bind(view: TerminalView) {
        if (demo) return
        unbind(); error.value = null; ended.value = false
        watch = viewModelScope.launch {
            var previous: Terminal? = null
            var previousClient: HcClient? = null
            var wasConnected = false
            combine(connections.statuses, connections.terminals) { statuses, rows ->
                statuses.find { it.host.id == hostId }?.state to rows.find { it.hostId == hostId && it.terminal.id == id }
            }.collect { (state, row) ->
                blocked.value = row?.terminal?.status?.state == "blocked" && !row.stale
                val client = connections.client(hostId)
                if (state !is ConnectionState.Connected || client == null) {
                    stopStream(); wasConnected = false
                    recovering.value = state is ConnectionState.Reconnecting || state is ConnectionState.Connecting
                    error.value = (state as? ConnectionState.Failed)?.error
                        ?: if (state == null) HcError("not_found", "Host removed") else null
                    return@collect
                }
                if (!previewOnly && sourceClient !== client) followTranscript(client)
                val recoveredConnection = !wasConnected || client !== previousClient
                wasConnected = true; previousClient = client
                if (recoveredConnection) { stopStream(); ended.value = false; error.value = null }
                // A reconnect's old snapshot is display-only. Wait for the fresh record.
                if (row == null || row.stale) {
                    if (remote != null) { stopStream(); ended.value = true }
                    recovering.value = !ended.value
                    return@collect
                }
                val terminal = row.terminal
                val recovery = terminal.attachRecovery(previous)
                previous = terminal
                when (recovery) {
                    AttachRecovery.WAIT_FOR_RESTART -> { stopStream(); recovering.value = true; ended.value = false }
                    AttachRecovery.FINISHED -> { stopStream(); recovering.value = false; ended.value = true }
                    AttachRecovery.REATTACH -> { stopStream(); ended.value = false; error.value = null; startStream(view, client) }
                    AttachRecovery.NONE -> if (remote == null && !ended.value && error.value == null) startStream(view, client)
                }
            }
        }
    }
    private fun startStream(view: TerminalView, client: HcClient) {
        val token = ++generation
        recovering.value = true
        remote = RemoteTerminalSession(view, client, id, viewModelScope, observe.value, { control.value },
            { if (generation == token) { ready.value = it; if (it) recovering.value = false } },
            { failure -> if (generation == token) { error.value = failure; recovering.value = false } },
            { reason -> if (generation == token) {
                recovering.value = reason.reason in setOf("disconnected", "lost", "restarted")
                ended.value = !recovering.value
            } }).also { it.start() }
    }
    private fun stopStream() { generation++; remote?.dispose(); remote = null; ready.value = false }
    fun unbind() {
        watch?.cancel(); watch = null; stopStream(); recovering.value = false
        previewJob?.cancel(); sourceWatch?.cancel(); sourceWatch = null
        source?.close(); source = null; sourceClient = null
        if (!demo) { transcriptState.value = null; chatItems.value = emptyList() }
        candidate.value = null; candidatePreview.value = null; candidateError.value = null
    }
    private fun followTranscript(client: HcClient) {
        sourceWatch?.cancel(); source?.close()
        sourceClient = client
        val transcript = TranscriptSource(client, id, viewModelScope)
        source = transcript
        sourceWatch = viewModelScope.launch {
            combine(transcript.state, transcript.items) { state, items -> state to items }.collect { (state, items) ->
                transcriptState.value = state; chatItems.value = visibleChatItems(state, items)
            }
        }
    }
    fun refreshTranscript() { viewModelScope.launch { try { source?.refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { error.value = e.asHcError() } } }
    fun findCandidates() { viewModelScope.launch { try { source?.candidates() } catch (e: CancellationException) { throw e } catch (e: Exception) { error.value = e.asHcError() } } }
    fun previewCandidate(value: TranscriptCandidate) {
        previewJob?.cancel(); candidate.value = value; candidatePreview.value = null; candidateError.value = null
        previewJob = viewModelScope.launch {
            candidateBusy.value = true
            try { candidatePreview.value = source?.preview(value) ?: throw HcError("io", "Transcript closed") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { candidateError.value = e.asHcError() }
            finally { candidateBusy.value = false }
        }
    }
    fun dismissCandidate() { previewJob?.cancel(); candidate.value = null; candidatePreview.value = null; candidateError.value = null }
    fun selectCandidate() {
        val value = candidate.value ?: return
        if (candidatePreview.value == null || candidateBusy.value) return
        previewJob = viewModelScope.launch {
            candidateBusy.value = true
            try { source?.select(value) ?: throw HcError("io", "Transcript closed"); candidate.value = null; selectedSurface.value = DetailSurface.CHAT }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { candidateError.value = e.asHcError() }
            finally { candidateBusy.value = false }
        }
    }
    fun choose(question: ChatItem.Question, option: ChatOption) {
        if (demo || !canChooseOption(chatItems.value, question, option, blocked.value, ready.value && !observe.value, transcriptState.value is TranscriptState.Ready)) return
        action { checkNotNull(connections.client(hostId)).keys(id, option.keys) }
    }
    fun applyPalette(dark: Boolean) {
        TerminalColors.COLOR_SCHEME.updateWith(AtomPalette.terminalProperties(dark))
        remote?.terminal?.emulator?.mColors?.reset()
        remote?.terminal?.onColorsChanged()
    }
    fun retryConnection() {
        if (connections.statuses.value.find { it.host.id == hostId }?.state !is ConnectionState.Connected) connections.reconnect(hostId)
    }
    fun keyCode(code: Int) { if (ready.value && !observe.value) remote?.keyCode(code, control.value) }
    fun key(value: String) { if (ready.value && !observe.value) remote?.write(value) }
    fun send() {
        if (observe.value || !ready.value || busy.value || draft.value.isBlank()) return
        val value = draft.value
        action { checkNotNull(connections.client(hostId)).send(id, value); if (draft.value == value) draft.value = "" }
    }
    fun interrupt() { if (!observe.value && ready.value) action { checkNotNull(connections.client(hostId)).interrupt(id) } }
    fun kill() { if (!observe.value && ready.value) action { checkNotNull(connections.client(hostId)).kill(id) } }
    fun read() = action { text.value = checkNotNull(connections.client(hostId)).read(id) }
    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true; error.value = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.asHcError() }
            finally { busy.value = false }
        }
    }
    override fun onCleared() { unbind() }
}

enum class DetailSurface { TERMINAL, CHAT }
