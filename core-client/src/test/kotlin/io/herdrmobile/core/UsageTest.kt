package io.herdrmobile.core

import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.usage.*
import io.herdrmobile.core.workspace.AgentKind
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

class UsageTest {
    private fun fixture(name: String) = javaClass.getResource("/transcripts/$name.jsonl")!!.readText().lines()
    private fun claude(model: String, tokens: Long) = projectSession(AgentKind.CLAUDE_CODE, listOf(
        """{"type":"assistant","message":{"model":"$model","usage":{"input_tokens":$tokens}}}"""
    ))!!
    private fun codex(total: Long, window: Long) = projectSession(AgentKind.CODEX, listOf(
        """{"type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"total_tokens":$total},"model_context_window":$window}}}"""
    ))!!.context!!
    @Test fun claudeUsesAllTokenFieldsAndIgnoresSidechainsAndSyntheticModels() {
        val info = projectSession(AgentKind.CLAUDE_CODE, fixture("claude-usage"))!!
        assertEquals("claude-opus-5-5", info.model)
        assertEquals(ContextUsage(90000,200000,45),info.context)
        assertNull(info.limits)
        assertEquals(1_000_000, claude("claude-opus[1m]",100).context!!.windowTokens)
        assertEquals(1_000_000, claude("claude-opus",200001).context!!.windowTokens)
        assertEquals(100, claude("claude-opus",2_000_000).context!!.usedPercent)
        assertEquals(0, claude("claude-opus",0).context!!.usedPercent)
    }
    @Test fun codexBaselineAndForeignLimits() {
        val info = projectSession(AgentKind.CODEX,fixture("codex-usage"),1234)!!
        assertEquals("gpt-6.1-sol",info.model)
        assertEquals(ContextUsage(62000,112000,50),info.context)
        val limits = info.limits!!
        assertEquals("plus",limits.plan)
        assertEquals(1234,limits.observedAtEpochMs)
        assertEquals(LimitWindow("primary",75.5,10080,2_000_000_000_000),limits.windows.single())
        assertEquals(0,codex(1000,112000).usedPercent)
        assertEquals(100,codex(200000,112000).usedPercent)
        assertEquals(100,codex(100,12000).usedPercent)
        assertEquals(100,codex(0,1000).usedPercent)
        assertNull(projectSession(AgentKind.CODEX,listOf("""{"type":"event_msg","payload":{"type":"token_count","rate_limits":{"limit_id":"other"}}}""")))
    }
    @Test fun absentInformationStaysNull() {
        for (kind in listOf(AgentKind.CLAUDE_CODE,AgentKind.CODEX)) {
            assertNull(projectSession(kind,listOf("malformed","{}")))
        }
        assertNull(projectSession(AgentKind.CLAUDE_CODE,listOf("""{"type":"assistant","message":{"model":"known"}}"""))!!.context)
    }
    @Test fun sessionSelectsFiveHoursThenShortestAndExcludesExpiredWindows() {
        val week=LimitWindow("week",50.0,10080,200)
        val session=LimitWindow("session",10.0,300,100)
        val short=LimitWindow("short",1.0,60,null)
        val limits=AgentLimits(null,listOf(week,session,short),0)
        assertEquals(session,limits.session(99))
        assertEquals(short,limits.session(100))
        assertEquals(week,AgentLimits(null,listOf(week),0).session(100))
        assertNull(AgentLimits(null,listOf(session),0).session(100))
        assertNull(AgentLimits(null,emptyList(),0).session())
    }
    @Test fun accountOutputParsesKnownWindowsAndReasons() {
        val account=parseClaudeAccount("""
            {"models":[{"value":"opus","resolvedModel":"claude-opus","displayName":"Opus","description":"Smart"}],
             "rate_limits_available":true,"subscription_type":"max",
             "rate_limits":{"five_hour":{"utilization":75.5,"resets_at":"2026-11-09T08:30:00.230684+00:00"},
                            "seven_day":{"utilization":30,"resets_at":null},
                            "seven_day_opus":{"utilization":10,"resets_at":"bad-date"},
                            "seven_day_sonnet":{"utilization":-1},
                            "unknown":{"utilization":99}}}
        """,42)
        assertEquals(AgentModel("opus","claude-opus","Opus","Smart"),account.models.single())
        assertEquals("max",account.limits!!.plan)
        assertEquals(42,account.limits!!.observedAtEpochMs)
        assertEquals(Instant.parse("2026-11-09T08:30:00.230Z").toEpochMilli(),account.limits!!.windows.first().resetsAtEpochMs)
        assertEquals(listOf(300,10080),account.limits!!.windows.map { it.windowMinutes })
        for(reason in listOf("program_missing","no_plan_limits","program_too_old")) {
            val unavailable=parseClaudeAccount("""{"models":[],"limitsUnavailable":"$reason"}""")
            assertNull(unavailable.limits)
            assertEquals(reason,unavailable.limitsUnavailable)
        }
        assertFails { parseClaudeAccount("""{"models":[],"error":"timeout"}""") }
        assertTrue(parseClaudeAccount("""{"models":[],"rate_limits_available":true,"rate_limits":{"five_hour":{"utilization":-1}}}""").limits!!.windows.isEmpty())
        assertFails { parseClaudeAccount("{}") }
    }
    @Test fun usageErrorsKeepModelsWithoutClaimingLimitsAreUnavailable() {
        for (error in listOf("timeout", "request_failed")) {
            val account = parseClaudeAccount("""{"models":[{"value":"opus","displayName":"Opus"}],"error":"$error"}""")
            assertEquals("opus", account.models.single().value)
            assertNull(account.limits)
            assertNull(account.limitsUnavailable)
            assertFails { parseClaudeAccount("""{"models":[],"error":"$error"}""") }
        }
    }
    @Test fun incompleteCodexCountsPreserveContextAndLimitsUseRowTimestamp() {
        val previous = fixture("codex-usage")
        for (info in listOf("null", "{}", "{\"model_context_window\":112000}", "{\"last_token_usage\":{\"total_tokens\":1}}")) {
            val row = """{"timestamp":"2026-11-09T08:30:00.230684+00:00","type":"event_msg","payload":{"type":"token_count","info":$info,"rate_limits":{"primary":{"used_percent":10}}}}"""
            val session = projectSession(AgentKind.CODEX, previous + row, 1234)!!
            assertEquals(ContextUsage(62000,112000,50), session.context)
            assertEquals(Instant.parse("2026-11-09T08:30:00.230Z").toEpochMilli(), session.limits!!.observedAtEpochMs)
        }
        val missing = """{"type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary":{"used_percent":10}}}}"""
        val session = projectSession(AgentKind.CODEX, previous + missing, 5678)!!
        assertEquals(ContextUsage(62000,112000,50), session.context)
        assertEquals(5678, session.limits!!.observedAtEpochMs)
    }

}
