package io.herdrmobile.core

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SessionDiscoveryScriptTest {
    @Test fun verifiesProcessIdentityAndEmptyConversationWithoutWritingAgentState() {
        val script=listOf("codex_store.py","discover.py").joinToString("\n") {
            javaClass.getResource("/io/herdrmobile/core/transcript/$it")!!.readText()
        }
        val harness=javaClass.getResource("/transcripts/discover_test.py")!!.readText()
        val process=ProcessBuilder("python3","-c",harness,script).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(20,TimeUnit.SECONDS),"Session discovery fixtures timed out")
            assertEquals(0,process.exitValue(),process.inputStream.bufferedReader().readText())
        } finally { process.destroyForcibly() }
    }
}
