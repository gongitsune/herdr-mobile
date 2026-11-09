package io.herdrmobile.core

import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ClaudeLocalCommandsTest {
    private fun user(id: String, parent: String?, text: String, meta: Boolean = false, sidechain: Boolean = false) = buildJsonObject {
        put("type", "user"); put("uuid", id); parent?.let { put("parentUuid", it) }
        put("isMeta", meta); put("isSidechain", sidechain)
        putJsonObject("message") { put("content", text) }
    }.toString()
    private val assistant = """{"type":"assistant","uuid":"a","message":{"model":"claude-opus-5-5","content":[{"type":"text","text":"Hello"}]}}"""
    private fun stdout(name: String) = "<local-command-stdout>Set model to `\u001b[1m$name\u001b[0m` and saved as your default for new sessions</local-command-stdout>"

    @Test fun commandsBecomeMessagesAndNoticesWithoutXmlOrAnsi() {
        val lines = listOf(assistant,
            user("c", "a", "<local-command-caveat>hidden</local-command-caveat>", true),
            user("cmd", "c", "<command-name>/model</command-name>\n<command-message>model</command-message>\n<command-args>sonnet</command-args>"),
            user("out", "cmd", stdout("Sonnet 5.5")),
            user("empty", "out", "<local-command-stdout>\u001b[0m  </local-command-stdout>"),
            user("meta", "empty", "hidden metadata", true),
            user("caveat", "meta", "<local-command-caveat>hidden even without isMeta</local-command-caveat>"))
        val items = projectTranscript(AgentKind.CLAUDE_CODE, lines, false)
        assertEquals(listOf("/model sonnet"), items.filterIsInstance<ChatItem.UserMessage>().map { it.text })
        val marker = items.filterIsInstance<ChatItem.TurnMarker>().single()
        assertEquals("local-command:out", marker.id)
        assertEquals("Set model to `Sonnet 5.5` and saved as your default for new sessions", marker.label)
        assertEquals("Hello", items.filterIsInstance<ChatItem.AssistantText>().single().markdown)
    }

    @Test fun switchedToUsesLatestCommandAfterAssistantAndIgnoresSidechainAndMeta() {
        val lines = listOf(assistant, user("one", "a", stdout("Sonnet 5.5")),
            user("two", "one", stdout("Opus 5.5")),
            user("side", "two", stdout("Sidechain"), sidechain = true),
            user("meta", "two", stdout("Metadata"), meta = true))
        val info = projectSession(AgentKind.CLAUDE_CODE, lines)!!
        assertEquals("Opus 5.5", info.switchedTo)
        assertEquals("claude-opus-5-5", info.model)
        assertNull(projectSession(AgentKind.CLAUDE_CODE, lines + assistant)!!.switchedTo)
        assertEquals("Sonnet 5.5", projectSession(AgentKind.CLAUDE_CODE, listOf(user("only", null, stdout("Sonnet 5.5"))))!!.switchedTo)
        assertNull(projectSession(AgentKind.CODEX, lines))
        assertNull(projectSession(AgentKind.CLAUDE_CODE, listOf(assistant, user("other", "a", "<local-command-stdout>Unrelated output</local-command-stdout>")))!!.switchedTo)
    }
}
