package io.herdrmobile.android.ui.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.core.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ConsoleViewModel(private val connections: HostConnections) : ViewModel() {
    val terminals = connections.terminals
    val hosts = connections.statuses
    val filter = MutableStateFlow<String?>(null)
    val error = MutableStateFlow(false)
    val creating = MutableStateFlow(false)
    fun create(host: String, command: String, cwd: String, done: (String) -> Unit) {
        if (creating.value) return
        viewModelScope.launch {
            creating.value = true; error.value = false
            try {
                val client = checkNotNull(connections.client(host))
                check(client.state.value is ConnectionState.Connected)
                done(client.run(line = command, cwd = cwd.trim().takeIf { it.isNotBlank() }).id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error.value = true }
            finally { creating.value = false }
        }
    }
}
