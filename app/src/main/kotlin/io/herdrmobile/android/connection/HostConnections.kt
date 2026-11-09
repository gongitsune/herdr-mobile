package io.herdrmobile.android.connection

import android.os.Looper
import androidx.annotation.MainThread
import io.herdrmobile.android.HostsRepository
import io.herdrmobile.android.SavedHost
import io.herdrmobile.core.*
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.workspace.WorkspaceStore
import io.herdrmobile.core.workspace.Space
import io.herdrmobile.core.rpc.HcError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HostStatus(val host: SavedHost, val state: ConnectionState)
data class ConsoleTerminal(val hostId: String, val hostName: String, val terminal: Terminal, val stale: Boolean = false,
    val subAgents: List<ConsoleTerminal> = emptyList(), val spaceName: String? = null, val tabName: String? = null)
data class HostWorkspace(val hostId: String, val hostName: String, val spaces: List<Space>, val error: HcError?, val staleTerminalIds: Set<String> = emptySet())
data class TrustRequest(val host: SavedHost, val fingerprint: String, val answer: CompletableDeferred<Boolean>)

/** One instance per Application. Each host has one SSH client and event store. */
class HostConnections(private val repository: HostsRepository, parentScope: CoroutineScope) {
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.Main.immediate)
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "HostConnections entries require Main" }
    private class Entry(var host: SavedHost, val scope: CoroutineScope, val client: HcClient, val store: TerminalStore, val workspace: WorkspaceStore)
    private val entries = mutableMapOf<String, Entry>()
    private val mutableStatuses = MutableStateFlow<List<HostStatus>>(emptyList())
    val statuses = mutableStatuses.asStateFlow()
    private val mutableTerminals = MutableStateFlow<List<ConsoleTerminal>>(emptyList())
    val terminals = mutableTerminals.asStateFlow()
    private val mutableWorkspaces = MutableStateFlow<List<HostWorkspace>>(emptyList())
    val workspaces = mutableWorkspaces.asStateFlow()
    val agents = workspaces.map(::workspaceAgents).stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val mutableTrust = MutableStateFlow<TrustRequest?>(null)
    val trust = mutableTrust.asStateFlow()
    private val trustLock = Mutex()

    init { scope.launch { repository.hosts.collect { hosts -> reconcile(hosts) } } }

    private fun reconcile(hosts: List<SavedHost>) {
        checkMain()
        entries.keys.toList().forEach { id ->
            val entry = entries.getValue(id)
            val host = hosts.find { it.id == id }
            // Saving an approved fingerprint must not tear down the ongoing handshake.
            if (host == null || host.copy(hostKeyFingerprint = entry.host.hostKeyFingerprint) != entry.host) remove(id)
            else entry.host = host
        }
        hosts.forEach { host -> if (host.id !in entries) create(host) }
        publish()
    }
    private fun create(host: SavedHost) {
        checkMain()
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val client = HcClient(TransportFactory {
            val current = repository.hosts.first().find { it.id == host.id } ?: throw HcError("io", "Host removed")
            val auth = try { repository.auth(current) } catch (e: CancellationException) { throw e }
                catch (e: Exception) { throw HcError("auth", "Unable to decrypt saved credentials") }
            SshTransportFactory(SshConfig(current.host, current.port, current.user, auth,
                coreSession = current.session, hostKeyFingerprint = current.hostKeyFingerprint)) { fingerprint ->
                trustLock.withLock {
                    val request = TrustRequest(current, fingerprint, CompletableDeferred())
                    mutableTrust.value = request
                    try {
                        val accepted = withTimeoutOrNull(120_000) { request.answer.await() } == true
                        accepted && repository.trust(current, fingerprint)
                    } finally { if (mutableTrust.value === request) mutableTrust.value = null }
                }
            }.open()
        }, child)
        val entry = Entry(host, child, client, TerminalStore(client, child), WorkspaceStore(client, child))
        entries[host.id] = entry
        child.launch { client.state.collect { publish() } }
        child.launch { entry.store.terminals.collect { publish() } }
        child.launch { entry.store.staleTerminals.collect { publish() } }
        child.launch { entry.workspace.spaces.collect { publish() } }
        child.launch { entry.workspace.error.collect { publish() } }
        child.launch { try { client.connect() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* state holds the classified error */ } }
    }
    private fun publish() {
        checkMain()
        mutableWorkspaces.value = entries.values.map { HostWorkspace(it.host.id, it.host.name, it.workspace.spaces.value, it.workspace.error.value,
            it.store.staleTerminals.value.map { terminal -> terminal.id }.toSet()) }
        mutableStatuses.value = entries.values.map { HostStatus(it.host, it.client.state.value) }
        mutableTerminals.value = entries.values.flatMap { entry ->
            consoleRows(entry.host.id, entry.host.name, entry.client.state.value,
                entry.store.terminals.value, entry.store.staleTerminals.value)
        }.sortedWith(compareBy<ConsoleTerminal> { it.terminal.priority }.thenBy { it.hostName }.thenBy { it.terminal.id })
    }
    private fun remove(id: String) { checkMain(); entries.remove(id)?.let { it.workspace.close(); it.client.close(); it.scope.cancel() } }
    fun reconnect(id: String) {
        scope.launch {
            checkMain()
            val client = entries[id]?.client ?: return@launch
            try { client.reconnectNow() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* HcClient exposes the failure kind through state. */ }
        }
    }
    @MainThread
    fun client(id: String): HcClient? { checkMain(); return entries[id]?.client }
    fun answerTrust(request: TrustRequest, accept: Boolean) {
        if (mutableTrust.value === request) request.answer.complete(accept)
    }
}
