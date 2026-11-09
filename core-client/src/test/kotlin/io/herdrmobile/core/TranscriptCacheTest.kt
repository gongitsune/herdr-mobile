package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.workspace.AgentKind
import org.junit.jupiter.api.Test
import kotlin.test.*

class TranscriptCacheTest {
    private fun entry(terminal:Terminal,path:String="/session.jsonl"):CachedTranscript {
        val binding=TranscriptBinding(AgentKind.CODEX,terminal.id,path,"process-${terminal.id}")
        return CachedTranscript(TranscriptProcess.of(terminal),binding,TranscriptState.Ready(binding,true),
            emptyList(),null,null,emptyList(),1,null,0,null,emptyList(),true,true,0,false,null)
    }

    @Test fun projectionsUseSixteenEntryLruAcrossTerminalsAndBindings() {
        val cache=TranscriptCache()
        for(i in 1..16) {
            val terminal=Terminal(address="t$i",pid=i.toLong())
            cache.put(terminal,entry(terminal))
        }
        assertNotNull(cache.latest("t1")) // Reading refreshes recency.
        val next=Terminal(address="t17",pid=17)
        cache.put(next,entry(next))
        assertNull(cache.latest("t2"))
        assertNotNull(cache.latest("t1"))
        for(i in 1..16) cache.put(next,entry(next,"/new-$i.jsonl"))
        assertNull(cache.latest("t1"),"The limit applies per binding, even for one terminal")
        assertEquals("/new-16.jsonl",cache.latest("t17")?.binding?.path)
        cache.invalidate("t17")
        assertNull(cache.latest("t17"))
    }

    @Test fun retainedEvidenceIsBoundedAndProcessScoped() {
        val cache=TranscriptCache()
        val terminal=Terminal(address="t1",pid=1)
        val binding=entry(terminal).binding
        cache.retain("t1","first",binding to 100)
        assertNull(cache.retained("t1","second"))
        cache.retain("t1","second",binding to 200)
        assertNull(cache.retained("t1","first"))
        assertEquals(200,cache.retained("t1","second")?.second)
        for(i in 2..18) cache.retain("t$i","process",binding to i.toLong())
        assertNull(cache.retained("t1","second"))
    }

    @Test fun processFingerprintIgnoresDisplayChangesButDetectsRestart() {
        val terminal=Terminal(address="t1",pid=1,foreground=Foreground(pid=2,argv=listOf("codex")),created_ms=100)
        assertEquals(TranscriptProcess.of(terminal),TranscriptProcess.of(terminal.copy(title="new",turn=2,status=Status(state="working"))))
        assertNotEquals(TranscriptProcess.of(terminal),TranscriptProcess.of(terminal.copy(foreground=Foreground(pid=3))))
    }
}
