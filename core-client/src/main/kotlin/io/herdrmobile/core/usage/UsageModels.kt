package io.herdrmobile.core.usage

data class LimitWindow(val id: String, val usedPercent: Double, val windowMinutes: Int?, val resetsAtEpochMs: Long?)
data class AgentLimits(val plan: String?, val windows: List<LimitWindow>, val observedAtEpochMs: Long) {
    fun session(nowMs: Long = System.currentTimeMillis()): LimitWindow? {
        val active = windows.filter { it.resetsAtEpochMs == null || it.resetsAtEpochMs > nowMs }
        return active.firstOrNull { it.windowMinutes == 300 }
            ?: active.filter { it.windowMinutes != null }.minByOrNull { it.windowMinutes!! }
    }
}
data class AgentModel(val value: String, val resolvedModel: String?, val displayName: String, val description: String?)
data class ClaudeAccount(val models: List<AgentModel>, val limits: AgentLimits?, val limitsUnavailable: String? = null)
