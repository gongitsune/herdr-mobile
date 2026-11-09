package io.herdrmobile.core

import io.herdrmobile.core.transcript.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CodexCandidateReadTest {
    private val answer="The assistant produced this distinctive detailed answer about concurrency, terminal sessions and transcript ownership."
    private val screen="• $answer"
    private val rows=(0..15).map { buildJsonObject { put("path","/$it.jsonl") } }

    @Test fun firstEightReadWithConcurrencyFourAndRestOnlyOnMiss():Unit=runBlocking {
        for(owner in listOf(3,12,-1)) {
            val reads=ConcurrentLinkedQueue<String>()
            val active=AtomicInteger()
            val peak=AtomicInteger()
            val result=stagedCodexScreenMatch(screen,rows) { row ->
                val path=row["path"]!!.jsonPrimitive.content
                val count=active.incrementAndGet()
                peak.updateAndGet { maxOf(it,count) }
                try {
                    reads.add(path)
                    delay(10)
                    CodexScreenTranscript(path,if(path=="/$owner.jsonl") listOf(answer) else emptyList(),"prompt $path",null)
                } finally { active.decrementAndGet() }
            }
            assertEquals(if(owner<0) null else "/$owner.jsonl",result)
            assertEquals(4,peak.get())
            assertEquals((0 until if(owner==3) 8 else 16).map { "/$it.jsonl" }.toSet(),reads.toSet())
            assertEquals((0..7).map { "/$it.jsonl" }.toSet(),reads.take(8).toSet())
        }
    }

    @Test fun unreadableCandidatePreventsAStageFromClaimingUniqueOwnership():Unit=runBlocking {
        assertNull(stagedCodexScreenMatch(screen,rows) { row ->
            val path=row["path"]!!.jsonPrimitive.content
            when(path) {
                "/0.jsonl" -> CodexScreenTranscript(path,listOf(answer),null,null)
                "/1.jsonl" -> null
                else -> CodexScreenTranscript(path,emptyList(),null,null)
            }
        })
    }

    @Test fun tailScriptReadsAtMost256KiBAndDropsIncompleteFirstLine() {
        val file=Files.createTempFile("codex-tail-", ".jsonl")
        try {
            // Use multibyte text to test the byte bound, including a huge individual JSONL line.
            val final="""{"type":"event_msg","payload":{"type":"agent_message","message":"$answer"}}"""+"\n"
            Files.writeString(file,"あ".repeat(CODEX_TAIL_BYTES)+"\n"+final)
            fun read():ByteArray {
                val process=ProcessBuilder("python3","-c",CODEX_TAIL_SCRIPT,file.toString()).start()
                val bytes=process.inputStream.readBytes()
                assertTrue(process.waitFor(5,TimeUnit.SECONDS))
                assertEquals(0,process.exitValue(),process.errorStream.bufferedReader().readText())
                assertTrue(bytes.size<=CODEX_TAIL_BYTES)
                return bytes
            }
            assertEquals(final,read().decodeToString())
            Files.writeString(file,final)
            assertEquals(final,read().decodeToString())
        } finally { Files.deleteIfExists(file) }
    }
}
