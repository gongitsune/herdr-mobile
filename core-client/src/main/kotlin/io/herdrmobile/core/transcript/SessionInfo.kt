package io.herdrmobile.core.transcript

data class ContextUsage(val usedTokens: Long, val windowTokens: Long, val usedPercent: Int)
data class SessionInfo(val model: String?, val context: ContextUsage?, val limits: io.herdrmobile.core.usage.AgentLimits? = null, val switchedTo: String? = null)
