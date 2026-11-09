package io.herdrmobile.core

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AgentCommandScriptTest {
    @Test fun validatesCatalogsUsesOnlyCatalogRequestsAndReapsProcessGroups() {
        val claude = javaClass.getResource("/io/herdrmobile/core/usage/claude_account.py")!!.readText()
        val codex = javaClass.getResource("/io/herdrmobile/core/usage/codex_commands.py")!!.readText()
        val harness = javaClass.getResource("/usage/command_exchange_test.py")!!.readText()
        val process = ProcessBuilder("python3", "-c", harness, claude, codex).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Command fixtures timed out")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.contains("command exchange fixtures passed"))
        } finally { process.destroyForcibly() }
    }
}
