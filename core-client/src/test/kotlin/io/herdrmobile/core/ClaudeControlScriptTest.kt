package io.herdrmobile.core

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ClaudeControlScriptTest {
    @Test fun filtersControlResponsesAndReapsTheProcessGroup() {
        val script=javaClass.getResource("/io/herdrmobile/core/usage/claude_account.py")!!.readText()
        val harness=javaClass.getResource("/usage/control_exchange_test.py")!!.readText()
        val process=ProcessBuilder("python3","-c",harness,script).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(10,TimeUnit.SECONDS),"Control fixtures timed out")
            val output=process.inputStream.bufferedReader().readText()
            assertEquals(0,process.exitValue(),output)
            assertTrue(output.contains("control exchange fixtures passed"))
        } finally { process.destroyForcibly() }
    }
}
