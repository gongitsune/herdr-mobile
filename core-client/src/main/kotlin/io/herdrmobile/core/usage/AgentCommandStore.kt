package io.herdrmobile.core.usage

import io.herdrmobile.core.HcClient
import io.herdrmobile.core.files.execRead
import io.herdrmobile.core.files.requireSuccess
import io.herdrmobile.core.rpc.HcError
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/** Command snapshots are scoped to an agent, machine, and working directory. */
class AgentCommandStore(private val client: HcClient, scope: CoroutineScope) : Closeable {
    private val owned = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private data class Key(val kind: AgentKind, val on: String?, val cwd: String)
    private class Entry {
        val flow = MutableStateFlow<List<AgentCommand>?>(null)
        val mutex = Mutex()
        var lastAttempt: Long? = null
    }
    private val entries = mutableMapOf<Key, Entry>()
    private val claudeScript = resource("claude_account.py")
    private val codexScript = resource("codex_commands.py")

    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/io/herdrmobile/core/usage/$name"))
        .bufferedReader().use { it.readText() }

    @Synchronized private fun entry(key: Key) = entries.getOrPut(key) { Entry() }

    fun commands(kind: AgentKind, on: String?, cwd: String): StateFlow<List<AgentCommand>?> {
        val existing: Entry
        val created: Boolean
        synchronized(this) {
            val key = Key(kind, on, cwd)
            created = !entries.containsKey(key)
            existing = entry(key)
        }
        if (created) owned.launch { refresh(kind, on, cwd) }
        return existing.flow.asStateFlow()
    }

    /** Five-minute throttle includes failed attempts, separately for each (kind, machine, cwd). */
    suspend fun refresh(kind: AgentKind, on: String?, cwd: String, force: Boolean = false) {
        if (!owned.isActive) return
        val task = owned.async {
            val entry = entry(Key(kind, on, cwd))
            entry.mutex.withLock {
                val now = System.nanoTime() / 1_000_000
                if (!force && entry.lastAttempt?.let { now - it < 300_000 } == true) return@withLock
                entry.lastAttempt = now
                if (kind != AgentKind.CLAUDE_CODE && kind != AgentKind.CODEX) {
                    entry.flow.value = emptyList()
                    return@withLock
                }
                try {
                    val argv = if (kind == AgentKind.CLAUDE_CODE) listOf("python3", "-c", claudeScript, "commands", cwd)
                        else listOf("python3", "-c", codexScript, cwd)
                    val result = withTimeout(22_000) {
                        client.execRead(argv, cwd = cwd, on = on, timeout = 20).requireSuccess()
                    }
                    if (result.truncated) throw HcError("io", "Agent command response was truncated")
                    val parsed = parseAgentCommands(kind, result.stdout.decodeToString())
                    if (parsed != null) entry.flow.value = parsed
                } catch (_: TimeoutCancellationException) {
                    // Both scripts enforce their own deadline and reap their process groups.
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    // Never expose remote stderr; keep the previous successful snapshot.
                }
                if (entry.flow.value == null) entry.flow.value = CommandCatalog.builtins(kind)
            }
        }
        try { task.await() } finally { task.cancel() }
    }

    override fun close() { owned.cancel() }
}
