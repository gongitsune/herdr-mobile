package io.herdrmobile.core

import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.workspace.AgentKind
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

class TranscriptMetadataTest {
    private val at="2030-01-01T00:00:00Z"
    private fun json(value:String)=HcJson.parseToJsonElement(value)
    @Test fun pollingUsesStatusAndFifteenSecondMonotonicWindow() {
        var now=0L
        val policy=TranscriptPolling(1500) { now }
        assertEquals(4000,policy.delayMs("idle"))
        assertEquals(1000,policy.delayMs("working"))
        assertEquals(1000,policy.delayMs("blocked"))
        policy.changed()
        now=14_999
        assertEquals(1000,policy.delayMs(null))
        now=15_000
        assertEquals(4000,policy.delayMs("done"))
        assertEquals(1000,policy.delayMs("working"))
        policy.changed() // nudge/status/data changes all extend the window.
        now=29_999
        assertEquals(1000,policy.delayMs("idle"))
        now=30_000
        assertEquals(4000,policy.delayMs("idle"))
    }
    @Test fun activityKeepsTheRecordedTurnStartAndChangesStepsWithoutUsingTheLocalClock() {
        val start=Instant.parse(at).toEpochMilli()
        val prefix=listOf(ChatItem.TurnMarker("start","Turn started",start),ChatItem.UserMessage("user","request",start+500))
        val thinking=turnActivity(prefix+ChatItem.Thinking("thinking","reason",start+1000))!!
        assertEquals(TurnActivity(true,start,"Thinking"),thinking)
        val tool=turnActivity(prefix+ChatItem.ToolCall("call","Read","{}",atEpochMs=start+2000))!!
        assertEquals(TurnActivity(true,start,"Read"),tool)
        val completed=turnActivity(prefix+ChatItem.TurnMarker("end","Turn complete",start+7000))!!
        assertFalse(completed.running)
        assertEquals(start,completed.startedAtEpochMs)
        val next=turnActivity(prefix+listOf(ChatItem.TurnMarker("end","Turn complete",start+7000),ChatItem.UserMessage("next","next prompt",start+9000)))!!
        assertEquals(start+9000,next.startedAtEpochMs)
        assertTrue(next.running)
        assertNull(turnActivity(listOf(ChatItem.UserMessage("unknown","without a host timestamp")))!!.startedAtEpochMs)
    }
    @Test fun knownToolIdentifiersHaveVerbsAndUnknownOrSimilarNamesDoNot() {
        val claude=AgentKind.CLAUDE_CODE
        assertEquals("Ran" to "echo fixture",toolPresentation(claude,"Bash",json("""{"command":"echo fixture"}""")))
        assertEquals("Read" to "src/A.kt",toolPresentation(claude,"Read",json("""{"file_path":"src/A.kt"}""")))
        for(name in listOf("Edit","MultiEdit")) assertEquals("Edited" to "src/A.kt",toolPresentation(claude,name,json("""{"file_path":"src/A.kt"}""")))
        assertEquals("Wrote" to "new.txt",toolPresentation(claude,"Write",json("""{"file_path":"new.txt"}""")))
        for(name in listOf("Grep","Glob")) assertEquals("Searched" to "fixture.*",toolPresentation(claude,name,json("""{"pattern":"fixture.*"}""")))
        assertEquals("Fetched" to "https://example.test",toolPresentation(claude,"WebFetch",json("""{"url":"https://example.test"}""")))
        for(name in listOf("bash","ReadExtra","mcp__tool__Read","WebSearch","SomeBash"))
            assertEquals(null to null,toolPresentation(claude,name,json("""{"file_path":"must-not-guess","command":"must-not-guess"}""")))
        val longPath="src/"+"a".repeat(600)+".kt"
        val input=kotlinx.serialization.json.buildJsonObject { put("file_path",kotlinx.serialization.json.JsonPrimitive(longPath)) }
        assertEquals("Read" to longPath,toolPresentation(claude,"Read",input))
        val codex=AgentKind.CODEX
        for(name in listOf("exec_command","shell","local_shell_call"))
            assertEquals("Ran" to "echo fixture",toolPresentation(codex,name,json("""{"cmd":"echo fixture"}""")))
        val patch="*** Begin Patch\n*** Update File: src/A.kt\n@@\n-a\n+b\n*** Add File: src/B.kt\n+x\n*** End Patch"
        assertEquals("Edited" to "src/A.kt (+1)",toolPresentation(codex,"apply_patch",kotlinx.serialization.json.JsonPrimitive(patch)))
        val wrapped=kotlinx.serialization.json.buildJsonObject { put("patch",kotlinx.serialization.json.JsonPrimitive(patch)) }.toString()
        assertEquals("Edited" to "src/A.kt (+1)",toolPresentation(codex,"apply_patch",kotlinx.serialization.json.JsonPrimitive(wrapped)))
        for(name in listOf("Bash","my_exec_command","CommandExecution","apply_patch_extra"))
            assertEquals(null to null,toolPresentation(codex,name,json("""{"cmd":"must-not-guess"}""")))
    }
    @Test fun claudeRowsCarryTheHostTimestampAndToolMetadata() {
        val rows=listOf(
            """{"timestamp":"$at","type":"user","uuid":"u","parentUuid":null,"sessionId":"fixture","message":{"content":"request"}}""",
            """{"timestamp":"$at","type":"assistant","uuid":"a","parentUuid":"u","message":{"id":"a","content":[{"type":"thinking","thinking":"reason"},{"type":"tool_use","id":"call","name":"Read","input":{"file_path":"file.kt"}},{"type":"text","text":"text"}]}}""",
            """{"timestamp":"$at","type":"system","uuid":"end","parentUuid":"a","subtype":"turn_duration"}"""
        )
        val items=projectTranscript(AgentKind.CLAUDE_CODE,rows,false)
        assertTrue(items.all { it.atEpochMs==Instant.parse(at).toEpochMilli() })
        val tool=items.filterIsInstance<ChatItem.ToolCall>().single()
        assertEquals("Read",tool.verb); assertEquals("file.kt",tool.target)
        val activity=turnActivity(items)!!
        assertFalse(activity.running)
        assertEquals(Instant.parse(at).toEpochMilli(),activity.startedAtEpochMs)
        val withoutTimestamp=rows.map { it.replace("\"timestamp\":\"$at\",","") }
        assertTrue(projectTranscript(AgentKind.CLAUDE_CODE,withoutTimestamp,false).all { it.atEpochMs==null })
        val bad=rows.map { it.replace(at,"not-a-host-time") }
        assertTrue(projectTranscript(AgentKind.CLAUDE_CODE,bad,false).all { it.atEpochMs==null })
    }
    @Test fun codexToolOutputKeepsCallIdentityMetadataAndCallTimestamp() {
        val rows=listOf(
            """{"timestamp":"$at","type":"session_meta","payload":{"id":"fixture","history_mode":"legacy"}}""",
            """{"timestamp":"$at","type":"event_msg","payload":{"type":"task_started","turn_id":"t"}}""",
            """{"timestamp":"$at","type":"event_msg","payload":{"type":"user_message","message":"request"}}""",
            """{"timestamp":"$at","type":"response_item","payload":{"type":"function_call","name":"exec_command","call_id":"call","arguments":"{\"cmd\":\"echo fixture\"}"}}""",
            """{"timestamp":"2030-01-01T00:00:07Z","type":"response_item","payload":{"type":"function_call_output","call_id":"call","output":"Exit code: 0"}}""",
            """{"timestamp":"2030-01-01T00:00:08Z","type":"event_msg","payload":{"type":"task_complete","turn_id":"t"}}"""
        )
        val items=projectTranscript(AgentKind.CODEX,rows,false)
        val tool=items.filterIsInstance<ChatItem.ToolCall>().single()
        assertEquals(ToolState.SUCCEEDED,tool.state)
        assertEquals("Ran",tool.verb); assertEquals("echo fixture",tool.target)
        assertEquals(Instant.parse(at).toEpochMilli(),tool.atEpochMs)
        assertEquals(Instant.parse("2030-01-01T00:00:08Z").toEpochMilli(),items.filterIsInstance<ChatItem.TurnMarker>().last().atEpochMs)
    }
}
