package io.herdrmobile.core

import io.herdrmobile.core.usage.*
import io.herdrmobile.core.workspace.AgentKind
import org.junit.jupiter.api.Test
import kotlin.test.*

class AgentCommandsTest {
    @Test fun claudeNativeCommandsKeepHintsAndOverrideTerminalInteraction() {
        val commands = parseAgentCommands(AgentKind.CLAUDE_CODE, """{"commands":[
            {"name":"local","description":"Project command","argumentHint":"<file>"},
            {"name":"model","description":"Native description","argumentHint":"[model]","builtin":true},
            {"name":"config","description":"Native config","builtin":true},
            {"name":"agents","description":"hidden","builtin":true},
            {"name":"heapdump","description":"hidden","builtin":true},
            {"name":"setup-bedrock","description":"hidden","builtin":true},
            {"name":"setup-vertex","description":"hidden","builtin":true},
            {"name":"custom","description":"Custom command","builtin":false},
            {"name":"bad","description":"Malformed flag","builtin":"true"},
            {"name":"/bad","description":"Bad name"},
            {"name":"bad name","description":"Bad name"},
            {"name":"","description":"Empty name"},
            {"name":"no-description"},false
        ]}""")!!
        val model = commands.single { it.name == "model" }
        assertEquals(AgentCommand("model", "Native description", "[model]"), model)
        assertEquals("Native config", commands.single { it.name == "config" }.description)
        assertEquals(CommandRun.TERMINAL, commands.single { it.name == "config" }.run)
        assertEquals(CommandRun.TERMINAL, commands.single { it.name == "permissions" }.run)
        assertEquals(CommandRun.TERMINAL, commands.single { it.name == "resume" }.run)
        assertEquals(AgentCommand("local", "Project command", "<file>", source = "custom"), commands.single { it.name == "local" })
        assertEquals("custom", commands.single { it.name == "custom" }.source)
        assertTrue(commands.none { it.name in setOf("agents", "heapdump", "setup-bedrock", "setup-vertex", "bad", "/bad", "bad name", "", "no-description") })
        assertEquals(listOf("builtin", "custom"), commands.map { it.source }.distinct())
    }

    @Test fun codexUsesStaticBuiltinsBeforeEnabledSkillsAndHidesDebugCommands() {
        val commands = parseAgentCommands(AgentKind.CODEX, """{"commands":[
            {"name":"review","description":"Shadow builtin"},
            {"name":"local-skill","description":"First skill"},
            {"name":"local-skill","description":"Duplicate skill"},
            {"name":"apps","description":"Hidden"}
        ]}""")!!
        assertEquals("model", commands.first().name)
        assertEquals('/', commands.single { it.name == "review" }.trigger)
        assertEquals("Review my current changes and find issues", commands.single { it.name == "review" }.description)
        assertEquals(AgentCommand("local-skill", "First skill", trigger = '$', source = "skill"), commands.last())
        assertTrue(commands.none { it.name in setOf("apps", "debug-config", "debug-m-drop", "debug-m-update") })
        for (name in listOf("resume", "permissions", "skills", "theme", "logout")) {
            assertEquals(CommandRun.TERMINAL, commands.single { it.name == name }.run)
        }
        // Only terminalBound defines TERMINAL in the Phase 11 contract.
        assertEquals(CommandRun.CHAT, commands.single { it.name == "compact" }.run)
        assertEquals(listOf("builtin", "skill"), commands.map { it.source }.distinct())
    }

    @Test fun duplicateNamesPreferBuiltinThenCustomThenSkillAndNeverTheLastRow() {
        val commands = mergeAgentCommands(AgentKind.CLAUDE_CODE, listOf(
            AgentCommand("same", "skill", source = "skill"),
            AgentCommand("same", "custom", source = "custom"),
            AgentCommand("same", "builtin"),
            AgentCommand("same", "last builtin"),
            AgentCommand("config", "custom config", source = "custom"),
        ))
        assertEquals("builtin", commands.single { it.name == "same" }.description)
        assertEquals("builtin", commands.single { it.name == "config" }.source)
        assertEquals(CommandRun.TERMINAL, commands.single { it.name == "config" }.run)
        assertEquals(commands.size, commands.map { it.name }.distinct().size)
    }

    @Test fun unsuccessfulExchangeIsDifferentFromAnEmptySuccessfulCatalog() {
        for (text in listOf("null", "[]", "{}", """{"commands":false}""", """{"commands":[],"error":"timeout"}""",
            """{"commands":[],"limitsUnavailable":"program_missing"}""")) {
            assertNull(parseAgentCommands(AgentKind.CLAUDE_CODE, text))
        }
        assertTrue(parseAgentCommands(AgentKind.CODEX, """{"commands":[]}""")!!.isNotEmpty())
        assertTrue(parseAgentCommands(AgentKind.CLAUDE_CODE, """{"commands":[]}""")!!.isNotEmpty())
        assertEquals(emptyList(), CommandCatalog.builtins(AgentKind.OTHER))
    }
}
