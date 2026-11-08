package io.herdrmobile.android.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.herdrmobile.android.HostsRepository
import io.herdrmobile.android.SavedHost
import io.herdrmobile.android.R
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.core.SshKeys
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HostDraft(
    val original: SavedHost? = null, val name: String = "", val host: String = "", val port: String = "22",
    val user: String = "", val session: String = "default", val authType: String = "password",
    val password: String = "", val privatePem: ByteArray? = null, val authorizedKey: String = "",
) {
    val valid: Boolean get() = host.isNotBlank() && user.isNotBlank() && session.isNotBlank() &&
        (port.toIntOrNull() ?: 0) in 1..65535 && if (authType == "key")
        privatePem != null || original?.encryptedPrivateKey?.isNotEmpty() == true
        else password.isNotEmpty() || original?.encryptedPassword?.isNotEmpty() == true
}

class HostsViewModel(private val repository: HostsRepository, private val connections: HostConnections) : ViewModel() {
    val hosts = repository.hosts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val statuses = connections.statuses
    val draft = MutableStateFlow<HostDraft?>(null)
    val busy = MutableStateFlow(false)
    val generating = MutableStateFlow(false)
    val error = MutableStateFlow<Int?>(null)
    val deleting = MutableStateFlow<SavedHost?>(null)
    private var generation: Job? = null
    fun edit(host: SavedHost?) {
        generation?.cancel(); error.value = null
        draft.value = host?.let { HostDraft(it, it.name, it.host, it.port.toString(), it.user, it.session, it.authType, authorizedKey = it.authorizedKey) } ?: HostDraft()
    }
    fun update(transform: (HostDraft) -> HostDraft) { draft.value = draft.value?.let(transform) }
    fun dismiss() {
        if (busy.value) return
        generation?.cancel(); draft.value?.privatePem?.fill(0); draft.value = null; error.value = null
    }
    fun generate() {
        if (generating.value) return
        generation = viewModelScope.launch {
            generating.value = true; error.value = null
            try {
                val key = withContext(Dispatchers.Default) { SshKeys.generateEd25519("herdr-mobile") }
                draft.value?.privatePem?.fill(0)
                update { it.copy(privatePem = key.privatePem, authorizedKey = key.authorizedKey) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error.value = R.string.error_key }
            finally { generating.value = false }
        }
    }
    fun save() {
        val value = draft.value ?: return
        if (!value.valid || busy.value || generating.value) return
        viewModelScope.launch {
            busy.value = true; error.value = null
            try {
                val host = (value.original ?: SavedHost(name = "", host = "", user = "")).copy(
                    name = value.name.trim().ifBlank { value.host.trim() }, host = value.host.trim(), port = value.port.toInt(),
                    user = value.user.trim(), session = value.session.trim(), authType = value.authType, authorizedKey = value.authorizedKey)
                repository.save(host, value.password.takeIf { it.isNotEmpty() }, value.privatePem)
                value.privatePem?.fill(0); draft.value = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error.value = R.string.error_save }
            finally { busy.value = false }
        }
    }
    fun delete() {
        val host = deleting.value ?: return
        viewModelScope.launch {
            busy.value = true
            try { repository.delete(host.id); deleting.value = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error.value = R.string.error_delete }
            finally { busy.value = false }
        }
    }
    fun reconnect(id: String) = connections.reconnect(id)
    override fun onCleared() { draft.value?.privatePem?.fill(0) }
}
