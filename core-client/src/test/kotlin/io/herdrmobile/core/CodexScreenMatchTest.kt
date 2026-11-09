package io.herdrmobile.core

import io.herdrmobile.core.transcript.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CodexScreenMatchTest {
    private val long="The unique finished response contains enough varied characters to identify this conversation safely."
    private fun candidate(path:String,answer:String=long,prompt:String="question")=CodexScreenTranscript(path,listOf(answer),prompt,prompt to answer)
    @Test fun emptyStartupScreenRequiresHeaderAndUnsentComposer() {
        val start="OpenAI Codex (v0.fixture)\nmodel: fixture\n› Ask Codex to do anything"
        assertTrue(codexScreenNotStarted(start))
        assertTrue(codexScreenNotStarted("› old prompt\nWorked for 2s\n$start"))
        assertTrue(codexScreenNotStarted("OpenAI Codex (v1)\n\u001B[2m› \u001B[0m"))
        for(screen in listOf("", "› ", "OpenAI Codex (v1)",
            "OpenAI Codex (v1)\n› submitted prompt\n› ",
            "OpenAI Codex (v1)\n› submitted prompt",
            "OpenAI Codex (v1)\nWorked for 2s\n› ",
            "OpenAI Codex (v1)\n• answer\n› ")) {
            assertFalse(codexScreenNotStarted(screen),screen)
        }
    }
    @Test fun longAnswersMustBeWholeUniqueAndAfterLastHeader() {
        val a=candidate("a")
        assertEquals("a",matchCodexScreen("OpenAI Codex (v1)\n• $long",listOf(a)))
        assertNull(matchCodexScreen("• $long\nOpenAI Codex (v2)",listOf(a)))
        assertNull(matchCodexScreen("• prefix $long suffix",listOf(a)))
        assertNull(matchCodexScreen("› $long",listOf(a)))
        assertNull(matchCodexScreen("• $long",listOf(a,candidate("b"))))
        assertNull(matchCodexScreen("• "+"a".repeat(100),listOf(candidate("a","a".repeat(100)))))
        assertEquals("a",matchCodexScreen("• "+long.replace(" ","\n  "),listOf(a)))
    }
    @Test fun shortAnswersRequireUniqueCompletedPromptAndFooter() {
        val a=candidate("a","ok")
        val shown="› question\n\n• ok\n\n─ Worked for 2s ─\n› "
        assertEquals("a",matchCodexScreen(shown,listOf(a)))
        assertNull(matchCodexScreen("› question\n• ok",listOf(a)))
        assertNull(matchCodexScreen(shown,listOf(a,candidate("b","different"))))
        assertNull(matchCodexScreen(shown,listOf(a,candidate("b","ok"))))
        assertNull(matchCodexScreen(shown+"next prompt",listOf(a)))
        assertNull(matchCodexScreen(shown,listOf(a,CodexScreenTranscript("b",emptyList(),null,null))))
        assertEquals("a",matchCodexScreen(shown,listOf(a,candidate("b","other","other prompt"))))
    }
    @Test fun extractsLastCompletedTurnAndAssistantResponseItems() {
        val rows=listOf(
            """{"type":"event_msg","payload":{"type":"user_message","message":"first"}}""",
            """{"type":"response_item","payload":{"role":"assistant","content":[{"type":"output_text","text":"ok"}]}}""",
            """{"type":"event_msg","payload":{"type":"task_complete","last_agent_message":"ok"}}""",
            """{"type":"event_msg","payload":{"type":"user_message","message":"next"}}""")
        val c=codexScreenTranscript("a",rows)
        assertEquals("first" to "ok",c.completed)
        assertEquals("next",c.latestPrompt)
        assertEquals(listOf("ok"),c.answers)
    }
    @Test fun temporarySqliteInventory():Unit {
        val script=Files.createTempFile("codex-store-", ".py")
        try {
            val imports="import os, json, glob, re\n"
            Files.writeString(script,imports+checkNotNull(javaClass.getResourceAsStream("/io/herdrmobile/core/transcript/codex_store.py")).bufferedReader().use { it.readText() })
            val test=checkNotNull(javaClass.getResource("/transcripts/codex_store_test.py"))
            val process=ProcessBuilder("python3",java.io.File(test.toURI()).path,script.toString()).redirectErrorStream(true).start()
            assertTrue(process.waitFor(10,TimeUnit.SECONDS))
            assertEquals(0,process.exitValue(),process.inputStream.bufferedReader().readText())
        } finally { Files.deleteIfExists(script) }
    }
}
