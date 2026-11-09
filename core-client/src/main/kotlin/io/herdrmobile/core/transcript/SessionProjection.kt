package io.herdrmobile.core.transcript

import io.herdrmobile.core.usage.*
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.serialization.json.*
import kotlin.math.roundToInt
import java.time.OffsetDateTime

private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
internal fun projectSession(kind: AgentKind, lines: List<String>, nowMs: Long = System.currentTimeMillis()): SessionInfo? {
    val rows = parseLines(lines).filterNot { it.flag("isSidechain") || it.text("agentId").isNotEmpty() || it.text("teamName").isNotEmpty() }
    var model: String? = null
    var context: ContextUsage? = null
    var limits: AgentLimits? = null
    var switchedTo: String? = null
    if (kind == AgentKind.CLAUDE_CODE) {
        val lastAssistant = rows.indexOfLast { it.text("type") == "assistant" }
        switchedTo = rows.drop(lastAssistant + 1).filter { it.text("type") == "user" && !it.flag("isMeta") }
            .mapNotNull { switchedModel(contentText(it["message"].obj()["content"])) }.lastOrNull()
        val message = rows.lastOrNull { it.text("type") == "assistant" && it["message"].obj().text("model") != "<synthetic>" }?.get("message")?.obj()
        model = message?.text("model")?.takeIf { it.isNotBlank() }
        val usage = message?.get("usage") as? JsonObject
        if (usage != null && usage.long("input_tokens") != null) {
            val used = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens").sumOf { usage.long(it) ?: 0 }.coerceAtLeast(0)
            val window = if (model?.contains("[1m]") == true || used > 200_000) 1_000_000L else 200_000L
            context = ContextUsage(used, window, (used.toDouble() * 100 / window).roundToInt().coerceIn(0, 100))
        }
    } else if (kind == AgentKind.CODEX) {
        for (row in rows) {
            val payload = row["payload"].obj()
            if (row.text("type") == "turn_context") model = payload.text("model").takeIf { it.isNotBlank() }
            if (row.text("type") != "event_msg" || payload.text("type") != "token_count") continue
            val info = payload["info"].obj()
            val total = info["last_token_usage"].obj().long("total_tokens")
            val window = info.long("model_context_window")
            if (total != null && window != null) {
                val effective = window - 12_000
                val used = (total - 12_000).coerceAtLeast(0)
                val percent = if (effective <= 0) 100 else 100 - ((effective - used).coerceAtLeast(0).toDouble() * 100 / effective).roundToInt()
                context = ContextUsage(total, window, percent.coerceIn(0, 100))
            }
            val rate = payload["rate_limits"] as? JsonObject
            if (rate != null && rate.text("limit_id").let { it.isEmpty() || it == "codex" }) {
                val windows = listOf("primary", "secondary").mapNotNull { id ->
                    val w = rate[id] as? JsonObject ?: return@mapNotNull null
                    val percent = (w["used_percent"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: return@mapNotNull null
                    LimitWindow(id, percent, (w["window_minutes"] as? JsonPrimitive)?.intOrNull, w.long("resets_at")?.times(1000))
                }
                val observedAt = runCatching { OffsetDateTime.parse(row.text("timestamp")).toInstant().toEpochMilli() }.getOrNull() ?: nowMs
                limits = AgentLimits(rate.text("plan_type").takeIf { it.isNotBlank() }, windows, observedAt)
            }
        }
    }
    return if (model == null && context == null && limits == null && switchedTo == null) null else SessionInfo(model, context, limits, switchedTo)
}
