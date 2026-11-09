package io.herdrmobile.core.usage

import io.herdrmobile.core.HcClient
import io.herdrmobile.core.files.*
import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.rpc.HcError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.Closeable
import java.time.OffsetDateTime

internal fun parseClaudeAccount(text: String, nowMs: Long = System.currentTimeMillis()): ClaudeAccount {
    val root = HcJson.parseToJsonElement(text).jsonObject
    fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    val reason = root.string("limitsUnavailable")
    require(reason == null || reason in setOf("program_missing", "program_too_old", "no_plan_limits"))
    val models = root["models"]!!.jsonArray.mapNotNull {
        val m = it as? JsonObject ?: return@mapNotNull null
        val value = m.string("value") ?: return@mapNotNull null
        val name = m.string("displayName") ?: return@mapNotNull null
        AgentModel(value, m.string("resolvedModel"), name, m.string("description"))
    }
    if (root["error"] != null) {
        if (models.isEmpty()) throw HcError("io", "Claude account request failed")
        return ClaudeAccount(models, null)
    }
    val limits = if (reason == null) {
        require((root["rate_limits_available"] as? JsonPrimitive)?.booleanOrNull == true)
        val rates = root["rate_limits"]!!.jsonObject
        val windows = listOf("five_hour" to 300, "seven_day" to 10080, "seven_day_opus" to 10080, "seven_day_sonnet" to 10080).mapNotNull { (id, minutes) ->
            val w = rates[id] as? JsonObject ?: return@mapNotNull null
            val percent = (w["utilization"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if (!percent.isFinite() || percent < 0) return@mapNotNull null
            runCatching {
                val reset = w.string("resets_at")?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
                LimitWindow(id, percent, minutes, reset)
            }.getOrNull()
        }
        AgentLimits(root.string("subscription_type"), windows, nowMs)
    } else null
    return ClaudeAccount(models, limits, reason)
}

/** One read-only control exchange per machine and five minutes, including failed attempts. */
class ClaudeAccountStore(private val client: HcClient, scope: CoroutineScope) : Closeable {
    private val owned = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private class Entry {
        val flow = MutableStateFlow<ClaudeAccount?>(null)
        val mutex = Mutex()
        var lastAttempt: Long? = null
    }
    private val accounts = mutableMapOf<String?, Entry>()
    private val script = checkNotNull(javaClass.getResourceAsStream("/io/herdrmobile/core/usage/claude_account.py")).bufferedReader().use { it.readText() }
    @Synchronized private fun entry(on: String?) = accounts.getOrPut(on) { Entry() }
    fun account(on: String?): StateFlow<ClaudeAccount?> {
        val existing: Entry
        val created: Boolean
        synchronized(this) {
            created = !accounts.containsKey(on)
            existing = entry(on)
        }
        if (created) owned.launch { refresh(on) }
        return existing.flow.asStateFlow()
    }
    suspend fun refresh(on: String?, force: Boolean = false) {
        if (!owned.isActive) return
        val task = owned.async {
            val entry = entry(on)
            entry.mutex.withLock {
                val now = System.nanoTime() / 1_000_000
                if (!force && entry.lastAttempt?.let { now - it < 300_000 } == true) return@withLock
                entry.lastAttempt = now
                try {
                    val result = withTimeout(20_000) {
                        client.execRead(listOf("python3", "-c", script), on = on, timeout = 20).requireSuccess()
                    }
                    if (result.truncated) throw HcError("io", "Claude account response was truncated")
                    entry.flow.value = parseClaudeAccount(result.stdout.decodeToString())
                } catch (e: TimeoutCancellationException) {
                    // The remote script has its own deadline and reaps its process group.
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    // Keep the previous successful snapshot; never export remote stderr.
                }
            }
        }
        try { task.await() } finally { task.cancel() }
    }
    override fun close() { owned.cancel() }
}
