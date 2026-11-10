package io.herdrmobile.core

import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.wire.Frame
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TranscriptSourceTest {
    private val user="""{"type":"user","uuid":"u","parentUuid":null,"sessionId":"fixture-session","message":{"content":"Synthetic prompt"}}"""
    private val answer="""{"type":"assistant","uuid":"a","parentUuid":"u","sessionId":"fixture-session","message":{"id":"m","content":[{"type":"text","text":"Synthetic answer"}]}}"""
    @Test fun reopeningPublishesCachedProjectionBeforeAnyProbeAndReadsOnlyTheAppend():Unit=runBlocking {
        for(kind in listOf(AgentKind.CLAUDE_CODE,AgentKind.CODEX)) withTimeout(30000) {
            val metadata=if(kind==AgentKind.CLAUDE_CODE)
                answer.replace("\"id\":\"m\",","\"id\":\"m\",\"model\":\"claude-opus-5-5\",\"usage\":{\"input_tokens\":90000},")
            else """{"type":"turn_context","payload":{"model":"gpt-6.1-sol"}}"""
            val peer=TranscriptPeer((initialRows(kind)+metadata).joinToString("\n",postfix="\n"),kind,"working")
            // Queue source jobs on this test's event loop. Constructor-time cache assertions
            // must run before any background append can replace the cached projection.
            val scope=CoroutineScope(coroutineContext+SupervisorJob())
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            var source=TranscriptSource(client,"t1",scope)
            try {
                client.connect()
                source.state.first { it is TranscriptState.Ready }
                // Give the reader a substantial checkpoint, then reopen with additional bytes on disk.
                peer.text.updateAndGet { it+"{}\n".repeat(2100) }
                source.refresh()
                val checkpointLine=peer.text.get().count { it=='\n' }.toLong()
                val items=source.items.value
                val activity=source.activity.value
                val session=source.session.value
                assertNotNull(session)
                val probes=peer.probes.get()
                source.close()
                peer.offsets.clear()
                peer.peer.requests.clear()
                peer.text.updateAndGet { it+liveRows(kind).first()+"\n" }
                source=TranscriptSource(client,"t1",scope)
                assertIs<TranscriptState.Ready>(source.state.value)
                assertSame(items,source.items.value)
                assertEquals(activity,source.activity.value)
                assertEquals(session,source.session.value)
                assertEquals(probes,peer.probes.get())
                source.items.first { it.any { row -> row is ChatItem.Thinking } }
                // A slow observer may resume after a normal validation poll. Check RPC order
                // at the cached append read, rather than racing the poll's current probe count.
                val requests=peer.peer.requests.toList()
                val appendRead=requests.indexOfFirst {
                    FakeCoreTransport.method(it)=="file.read" && it["params"].obj()["limit"]?.jsonPrimitive?.int==2000
                }
                assertTrue(appendRead>=0,"Reopening must read the append")
                assertFalse(requests.take(appendRead).any { FakeCoreTransport.method(it)=="machine.exec" },
                    "Reopening must not rerun the resolver before publishing/reading the cache")
                assertTrue(peer.offsets.isNotEmpty())
                assertEquals(checkpointLine,peer.offsets.first(),"The first read must overlap the cached checkpoint line")
                assertTrue(peer.offsets.all { it>2000 },"Restore the reader checkpoint, not just displayed rows")
                val beforeRefresh=peer.probes.get()
                source.refresh()
                assertTrue(peer.probes.get()>beforeRefresh,"Normal validation still runs after a cached reopen")
                peer.notStarted=true
                source.refresh()
                assertIs<TranscriptState.NotStarted>(source.state.value)
                source.close()
                source=TranscriptSource(client,"t1",scope)
                assertIs<TranscriptState.Loading>(source.state.value,"/new must invalidate the previous projection")
            } finally { source.close(); client.close(); scope.cancel() }
        }
    }

    @Test fun initialConnectionAndResolverWaitStayLoadingAndDisconnectKeepsReady():Unit=runBlocking {
        withTimeout(6000) {
            val peer=TranscriptPeer(user+"\n").apply { holdProbes=true }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            val source=TranscriptSource(client,"t1",scope)
            try {
                delay(30)
                assertIs<TranscriptState.Loading>(source.state.value)
                client.connect()
                while(peer.pendingProbes.isEmpty()) delay(1)
                assertIs<TranscriptState.Loading>(source.state.value)
                peer.holdProbes=false
                peer.pendingProbes.forEach(peer::respondProbe)
                source.state.first { it is TranscriptState.Ready }
                val rows=source.items.value
                val state=source.state.value
                client.close()
                delay(50)
                assertEquals(state,source.state.value)
                assertSame(rows,source.items.value)
            } finally { source.close(); client.close(); scope.cancel() }
        }
    }

    @Test fun disconnectDuringResolutionKeepsLoadingOrReady():Unit=runBlocking {
        for(initial in listOf(true,false)) withTimeout(5000) {
            val peer=TranscriptPeer(user+"\n").apply { holdProbes=initial }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            val source=TranscriptSource(client,"t1",scope)
            try {
                client.connect()
                if(!initial) source.state.first { it is TranscriptState.Ready }
                val expected=source.state.value
                peer.holdProbes=true
                val refresh=if(!initial) async { source.refresh() } else null
                while(peer.pendingProbes.isEmpty()) delay(1)
                peer.peer.close()
                refresh?.await()
                client.state.first { it is ConnectionState.Failed }
                delay(30)
                assertEquals(expected,source.state.value)
            } finally { source.close(); client.close(); scope.cancel() }
        }
    }
    @Test fun notStartedReevaluatesOnPollingNudgeAndStatusAndClearsPreviousConversation():Unit=runBlocking {
        for(kind in listOf(AgentKind.CLAUDE_CODE,AgentKind.CODEX)) {
            for(trigger in listOf("poll","nudge","status","turn","partial")) withTimeout(6000) {
                val peer=TranscriptPeer("",kind).apply { notStarted=true }
                val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
                val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
                val source=TranscriptSource(client,"t1",scope,if(trigger in setOf("poll","partial")) 20 else 1500)
                try {
                    client.connect()
                    assertEquals(TranscriptState.NotStarted(kind),source.state.first { it is TranscriptState.NotStarted })
                    assertTrue(source.items.value.isEmpty()); assertNull(source.session.value)
                    source.candidates()
                    assertEquals(TranscriptState.NotStarted(kind),source.state.value)
                    if(trigger=="partial") {
                        peer.notStarted=false; peer.exact=false
                        source.refresh()
                        assertIs<TranscriptState.Unavailable>(source.state.value)
                        peer.exact=true
                    }
                    peer.text.set(initialRows(kind).joinToString("\n",postfix="\n"))
                    peer.notStarted=false
                    when(trigger) {
                        "nudge" -> source.nudge()
                        "status" -> peer.statusEvent("working")
                        "turn" -> peer.turnEvent()
                    }
                    withTimeout(750) { source.state.first { it is TranscriptState.Ready } }
                    assertTrue(source.items.value.any { it is ChatItem.UserMessage })
                    peer.notStarted=true
                    if(trigger=="partial") source.candidates() else source.refresh()
                    assertEquals(TranscriptState.NotStarted(kind),source.state.value)
                    assertTrue(source.items.value.isEmpty()); assertNull(source.session.value)
                } finally { source.close(); client.close(); scope.cancel() }
            }
        }
    }
    @Test fun appendOnlyReadRereadsPartialTailWithoutDuplicatesAndResetsAfterTruncation():Unit=runBlocking {
        withTimeout(6000) {
            val peer=TranscriptPeer(user+"\n")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            val source=TranscriptSource(client,"t1",scope,20)
            try {
                client.connect()
                source.items.first { it.filterIsInstance<ChatItem.UserMessage>().size==1 }
                val probes=peer.probes.get()
                peer.text.set(user+"\n"+answer.take(40))
                source.state.first { it is TranscriptState.Ready && !it.complete }
                assertEquals(1,source.items.value.size)
                peer.text.set(user+"\n"+answer+"\n")
                source.items.first { it.any { row -> row is ChatItem.AssistantText } }
                assertEquals(1,source.items.value.filterIsInstance<ChatItem.UserMessage>().size)
                delay(60)
                assertEquals(probes,peer.probes.get()) // Polling follows bytes, never rebinds by clock.
                assertTrue(peer.offsets.any { it==2L })
                peer.text.set(user.replace("Synthetic prompt","Replacement prompt")+"\n")
                source.items.first { it.singleOrNull()==ChatItem.UserMessage("u","Replacement prompt") }
                assertTrue(peer.offsets.count { it==1L }>=2)
                assertFalse(peer.peer.saw("doc.set"))
            } finally { source.close(); client.close(); scope.cancel() }
        }
    }
    @Test fun noExactEvidenceNeverAutoSelectsNewestAndConfirmationRequiresPreview():Unit=runBlocking {
        withTimeout(6000) {
            val peer=TranscriptPeer(user+"\n").apply { exact=false }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            var source=TranscriptSource(client,"t1",scope,20)
            try {
                client.connect()
                source.state.first { it is TranscriptState.Unavailable && it.reason=="Ambiguous fixture" }
                val probes=peer.probes.get()
                delay(60)
                assertEquals(probes,peer.probes.get())
                assertTrue(source.items.value.isEmpty())
                val candidates=source.candidates()
                assertEquals(1,candidates.size)
                assertFailsWith<IllegalArgumentException> { source.select(candidates.single()) }
                assertEquals("Synthetic prompt",source.preview(candidates.single()).filterIsInstance<ChatItem.UserMessage>().single().text)
                source.select(candidates.single())
                val ready=source.state.first { it is TranscriptState.Ready } as TranscriptState.Ready
                assertTrue(ready.binding.confirmed)
                source.close()
                val reads=peer.offsets.size
                source=TranscriptSource(client,"t1",scope)
                assertEquals(ready,source.state.value,"A confirmed manual selection survives reopening")
                while(peer.offsets.size==reads) delay(1) // The new source has received its terminal snapshot.
                source.refresh()
                assertEquals(ready,source.state.value)
                // A different process invalidates the manual choice and removes its rows.
                peer.process.set("t1:1:124:2")
                source.refresh()
                assertIs<TranscriptState.Unavailable>(source.state.value)
                assertTrue(source.items.value.isEmpty())
            } finally { source.close(); client.close(); scope.cancel() }
        }
    }
    @Test fun claudeAndCodexPublishBlocksBeforeTurnCompletion():Unit=runBlocking {
        for(kind in listOf(AgentKind.CLAUDE_CODE,AgentKind.CODEX)) withTimeout(8000) {
            val initial=initialRows(kind)
            val peer=TranscriptPeer(initial.joinToString("\n",postfix="\n"),kind,"working")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            val source=TranscriptSource(client,"t1",scope,20)
            try {
                client.connect()
                source.items.first { it.any { row -> row is ChatItem.UserMessage } }
                val started=java.time.Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()
                source.activity.first { it?.running==true && it.startedAtEpochMs==started }
                var expected=source.items.value.size
                val additions=liveRows(kind)
                for((i,line) in additions.take(3).withIndex()) {
                    peer.text.updateAndGet { it+line+"\n" }
                    val observed=source.items.first { it.size>expected }
                    assertEquals(expected+1,observed.size,"$kind block $i must appear before the turn ends")
                    expected=observed.size
                    assertTrue(source.activity.value?.running==true)
                    assertEquals(started,source.activity.value?.startedAtEpochMs)
                }
                val rows=source.items.value
                assertTrue(rows.any { it is ChatItem.Thinking })
                assertTrue(rows.any { it is ChatItem.ToolCall })
                assertTrue(rows.any { it is ChatItem.AssistantText && it.markdown=="Answer in progress" })
                assertTrue(rows.all { it.atEpochMs!=null })
                peer.text.updateAndGet { it+additions.last()+"\n" }
                source.items.first { it.any { row -> row is ChatItem.TurnMarker && row.label=="Turn complete" } }
                peer.statusEvent("idle")
                source.activity.first { it?.running==false }
                assertEquals(started,source.activity.value?.startedAtEpochMs)
            } finally { source.close(); client.close(); scope.cancel() }
        }
    }

    @Test fun oneRefreshDrainsMoreThanThreeThousandLinesForBothFormats():Unit=runBlocking {
        for(kind in listOf(AgentKind.CLAUDE_CODE,AgentKind.CODEX)) withTimeout(15000) {
            val peer=TranscriptPeer(initialRows(kind).joinToString("\n",postfix="\n"),kind,"working")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            val source=TranscriptSource(client,"t1",scope)
            try {
                client.connect()
                source.state.first { it is TranscriptState.Ready && it.complete }
                peer.offsets.clear()
                val added=(1..3500).map { index -> when(kind) {
                    AgentKind.CLAUDE_CODE -> """{"type":"assistant","uuid":"a$index","parentUuid":"${if(index==1) "u" else "a${index-1}"}","sessionId":"fixture-session","message":{"id":"m$index","content":[{"type":"text","text":"block $index"}]}}"""
                    else -> """{"ordinal":${index+2},"type":"item_completed","payload":{"turn_id":"turn","item":{"id":"a$index","type":"AgentMessage","content":[{"type":"Text","text":"block $index"}]}}}"""
                } }
                peer.text.updateAndGet { it+added.joinToString("\n",postfix="\n") }
                source.refresh()
                assertEquals(3500,source.items.value.filterIsInstance<ChatItem.AssistantText>().size)
                assertEquals("block 3500",source.items.value.filterIsInstance<ChatItem.AssistantText>().last().markdown)
                assertTrue((source.state.value as TranscriptState.Ready).complete)
                assertTrue(peer.offsets.size>=2,"Must read more than one page within refresh")
            } finally { source.close(); client.close(); scope.cancel() }
        }
    }

    @Test fun checkpointOnlyPageStillMakesProgressAndPartialEofDoesNotSpin():Unit=runBlocking {
        withTimeout(5000) {
            val peer=TranscriptPeer(user+"\n",pageSize=1)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            val source=TranscriptSource(client,"t1",scope)
            try {
                client.connect(); source.state.first { it is TranscriptState.Ready }
                peer.text.set(user+"\n"+answer+"\n"+"{incomplete")
                source.refresh()
                assertEquals(1,source.items.value.filterIsInstance<ChatItem.AssistantText>().size)
                assertFalse((source.state.value as TranscriptState.Ready).complete)
                assertTrue(peer.offsets.size<10)
            } finally { source.close(); client.close(); scope.cancel() }
        }
    }

    @Test fun nudgeReadsImmediatelyAndUnchangedPollsKeepThePublishedItems():Unit=runBlocking {
        withTimeout(5000) {
            val peer=TranscriptPeer(user+"\n")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            val source=TranscriptSource(client,"t1",scope)
            try {
                client.connect(); source.state.first { it is TranscriptState.Ready }
                delay(30)
                peer.text.set(user+"\n"+answer+"\n")
                source.nudge()
                withTimeout(750) { source.items.first { it.any { row -> row is ChatItem.AssistantText } } }
                source.close()
                val count=peer.offsets.size
                source.nudge(); delay(30)
                assertEquals(count,peer.offsets.size)
            } finally { source.close(); client.close(); scope.cancel() }
        }
        withTimeout(5000) {
            val peer=TranscriptPeer(user+"\n")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            val source=TranscriptSource(client,"t1",scope,20)
            try {
                client.connect(); source.state.first { it is TranscriptState.Ready }
                val original=source.items.value
                val count=AtomicInteger()
                val observer=launch(start=CoroutineStart.UNDISPATCHED) { source.items.drop(1).collect { count.incrementAndGet() } }
                val before=peer.offsets.size
                withTimeout(1000) { while(peer.offsets.size<before+3) delay(1) }
                assertSame(original,source.items.value)
                assertEquals(0,count.get())
                observer.cancelAndJoin()
            } finally { source.close(); client.close(); scope.cancel() }
        }
    }

    @Test fun sessionUpdatesWithReadsAndClearsAfterTruncation():Unit=runBlocking {
        withTimeout(6000) {
            val usage=answer.replace("\"id\":\"m\",", "\"id\":\"m\",\"model\":\"claude-opus-5-5\",\"usage\":{\"input_tokens\":90000},")
            val peer=TranscriptPeer(user+"\n"+usage+"\n")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer.peer },scope,autoReconnect=false)
            val source=TranscriptSource(client,"t1",scope,20)
            try {
                client.connect()
                val initial=source.session.first { it!=null }
                assertEquals(ContextUsage(90000,200000,45),initial!!.context)
                peer.text.updateAndGet { it+"{}\n" }
                source.refresh()
                assertSame(initial,source.session.value)
                peer.exact=false
                source.refresh()
                assertNull(source.session.value)
                peer.exact=true
                source.refresh()
                assertEquals(initial,source.session.value)
                peer.text.set(user+"\n")
                source.refresh()
                assertNull(source.session.value)
            } finally { source.close(); client.close(); scope.cancel() }
        }
    }

    private fun initialRows(kind:AgentKind):List<String> = if(kind==AgentKind.CLAUDE_CODE) listOf(
        user.dropLast(1)+",\"timestamp\":\"2030-01-01T00:00:00Z\"}"
    ) else listOf(
        """{"ordinal":0,"type":"session_meta","payload":{"id":"fixture-session","history_mode":"paginated"}}""",
        """{"ordinal":1,"timestamp":"2030-01-01T00:00:00Z","type":"event_msg","payload":{"type":"task_started","turn_id":"turn"}}""",
        """{"ordinal":2,"timestamp":"2030-01-01T00:00:00Z","type":"item_completed","payload":{"turn_id":"turn","item":{"id":"u","type":"UserMessage","content":[{"type":"text","text":"Synthetic prompt"}]}}}"""
    )
    private fun liveRows(kind:AgentKind):List<String> = if(kind==AgentKind.CLAUDE_CODE) listOf(
        """{"timestamp":"2030-01-01T00:00:01Z","type":"assistant","uuid":"thinking","parentUuid":"u","sessionId":"fixture-session","message":{"id":"thinking","content":[{"type":"thinking","thinking":"Thinking in progress"}]}}""",
        """{"timestamp":"2030-01-01T00:00:02Z","type":"assistant","uuid":"tool","parentUuid":"thinking","sessionId":"fixture-session","message":{"id":"tool","content":[{"type":"tool_use","id":"call","name":"Bash","input":{"command":"echo fixture"}}]}}""",
        """{"timestamp":"2030-01-01T00:00:03Z","type":"assistant","uuid":"answer","parentUuid":"tool","sessionId":"fixture-session","message":{"id":"answer","content":[{"type":"text","text":"Answer in progress"}]}}""",
        """{"timestamp":"2030-01-01T00:00:04Z","type":"system","subtype":"turn_duration","uuid":"end","parentUuid":"answer","sessionId":"fixture-session"}"""
    ) else listOf(
        """{"ordinal":3,"timestamp":"2030-01-01T00:00:01Z","type":"item_completed","payload":{"turn_id":"turn","item":{"id":"thinking","type":"Reasoning","summary_text":["Thinking in progress"]}}}""",
        """{"ordinal":4,"timestamp":"2030-01-01T00:00:02Z","type":"item_completed","payload":{"turn_id":"turn","item":{"id":"tool","type":"CommandExecution","command":["echo","fixture"],"status":"in_progress"}}}""",
        """{"ordinal":5,"timestamp":"2030-01-01T00:00:03Z","type":"item_completed","payload":{"turn_id":"turn","item":{"id":"answer","type":"AgentMessage","content":[{"type":"Text","text":"Answer in progress"}]}}}""",
        """{"ordinal":6,"timestamp":"2030-01-01T00:00:04Z","type":"event_msg","payload":{"type":"task_complete","turn_id":"turn"}}"""
    )

    private class TranscriptPeer(initial:String, val kind:AgentKind=AgentKind.CLAUDE_CODE,
                                 initialStatus:String?=null, val pageSize:Int=2000) {
        val peer=FakeCoreTransport()
        val text=AtomicReference(initial)
        val process=AtomicReference("t1:1:123:2")
        val offsets=ConcurrentLinkedQueue<Long>()
        val probes=AtomicInteger()
        @Volatile var holdProbes=false
        val pendingProbes=ConcurrentLinkedQueue<JsonObject>()
        private val seq=AtomicInteger()
        val status=AtomicReference(initialStatus)
        @Volatile var exact=true
        @Volatile var notStarted=false
        fun respondProbe(r:JsonObject) {
            val evidence=buildJsonObject {
                put("process_key",process.get()); put("reason","Ambiguous fixture")
                put("not_started",notStarted && kind==AgentKind.CLAUDE_CODE)
                put("no_new_threads",notStarted && kind==AgentKind.CODEX)
                put("process_started_ms",1000)
                if(exact && !notStarted && kind==AgentKind.CODEX) put("open_files",buildJsonArray { add("/fixture/session.jsonl") })
                if(exact && !notStarted) put("binding",buildJsonObject { put("session_id","fixture-session"); put("path","/fixture/session.jsonl") })
                put("candidates",buildJsonArray { add(buildJsonObject { put("session_id","fixture-session"); put("path","/fixture/session.jsonl") }) })
            }
            val encoded=Base64.getEncoder().encodeToString(evidence.toString().toByteArray())
            peer.respond(r,"""{"code":0,"stdout":"$encoded","stderr":"","truncated":false,"timed_out":false,"ms":1}""")
        }
        fun turnEvent() {
            peer.send(Frame(2,0,(buildJsonObject {
                put("method","event"); put("params",buildJsonObject {
                    put("seq",seq.incrementAndGet()); put("type","turn"); put("terminal","t1")
                    put("data",buildJsonObject { put("turn",1) })
                })
            }.toString()+"\n").toByteArray()))
        }
        fun statusEvent(word:String) {
            status.set(word)
            val message=buildJsonObject {
                put("method","event"); put("params",buildJsonObject {
                    put("seq",seq.incrementAndGet()); put("type","status"); put("terminal","t1")
                    put("data",buildJsonObject { put("status",buildJsonObject { put("state",word) }); put("records",JsonObject(emptyMap())); put("reported",true) })
                })
            }
            peer.send(Frame(2,0,(message.toString()+"\n").toByteArray()))
        }
        init { peer.intercept={ r ->
            val p=r["params"].obj()
            when(FakeCoreTransport.method(r)) {
                "events.subscribe" -> {
                    val command=if(kind==AgentKind.CLAUDE_CODE) "claude" else "codex"
                    val snapshot=buildJsonObject {
                        put("seq",seq.get()); put("channel",2)
                        put("terminals",buildJsonArray { add(buildJsonObject {
                            put("address","t1"); put("state","live"); put("pid",123); put("cwd","/fixture")
                            status.get()?.let { word -> put("status",buildJsonObject { put("state",word) }) }
                            put("foreground",buildJsonObject { put("pid",123); put("name",command); put("argv",buildJsonArray { add(command) }); put("cwd","/fixture") })
                        }) })
                    }
                    peer.respond(r,snapshot.toString()); true
                }
                "machine.exec" -> {
                    probes.incrementAndGet()
                    if(holdProbes) pendingProbes.add(r) else respondProbe(r)
                    true
                }
                "terminal.read" -> { peer.respond(r,"""{"text":"OpenAI Codex (v0.fixture)\n› Ask Codex to do anything"}"""); true }
                "file.read" -> {
                    val offset=p["offset"]!!.jsonPrimitive.long; val limit=p["limit"]!!.jsonPrimitive.int
                    if(limit==2000) offsets.add(offset)
                    val all=Regex("[^\\n]*\\n|[^\\n]+$").findAll(text.get()).map { it.value }.toList()
                    if(offset>all.size && offset>1) {
                        peer.send(Frame(0,0,("{\"id\":"+r["id"]+",\"error\":{\"code\":-32602,\"message\":\"offset past EOF\",\"data\":{\"kind\":\"invalid_params\"}}}").toByteArray()))
                    } else {
                        val given=all.drop((offset-1).toInt()).take(minOf(limit,pageSize))
                        peer.respond(r,buildJsonObject { put("text",given.joinToString("")); put("first",offset); put("last",offset+given.size-1); put("lines",all.size) }.toString())
                    }
                    true
                }
                else -> false
            }
        } }
    }
}
