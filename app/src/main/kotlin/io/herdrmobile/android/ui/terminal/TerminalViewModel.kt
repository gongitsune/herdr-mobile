package io.herdrmobile.android.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.view.TerminalView
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.android.ui.asHcError
import io.herdrmobile.core.*
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.rpc.HcError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class TerminalViewModel(private val connections: HostConnections, private val hostId: String, private val id: String) : ViewModel() {
    val observe = MutableStateFlow(false)
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
        unbind(); error.value = null; ended.value = false
        watch = viewModelScope.launch {
            var previous: Terminal? = null
            var previousClient: HcClient? = null
            var wasConnected = false
            combine(connections.statuses, connections.terminals) { statuses, rows ->
                statuses.find { it.host.id == hostId }?.state to rows.find { it.hostId == hostId && it.terminal.id == id }
            }.collect { (state, row) ->
                val client = connections.client(hostId)
                if (state !is ConnectionState.Connected || client == null) {
                    stopStream(); wasConnected = false
                    recovering.value = state is ConnectionState.Reconnecting || state is ConnectionState.Connecting
                    error.value = (state as? ConnectionState.Failed)?.error
                        ?: if (state == null) HcError("not_found", "Host removed") else null
                    return@collect
                }
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
    fun unbind() { watch?.cancel(); watch = null; stopStream(); recovering.value = false }
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
