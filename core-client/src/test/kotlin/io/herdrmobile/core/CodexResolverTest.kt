package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.transcript.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.*

class CodexResolverTest {
    @Test fun notStartedNeedsVerifiedInventoryAndAnEmptyScreen():Unit=runBlocking {
        val terminal=Terminal(address="t1",state="live",pid=11,cwd="/project",
            foreground=Foreground(pid=11,name="codex",argv=listOf("codex")))
        val peer=FakeCoreTransport()
        var absent=true
        var started:Long?=2000
        var screen="OpenAI Codex (v1)\n› Ask Codex to do anything"
        var locks=emptyList<String>()
        peer.intercept={ request ->
            when(FakeCoreTransport.method(request)) {
                "machine.exec" -> {
                    val info=buildJsonObject {
                        put("process_key","11:1:11:1"); put("no_new_threads",absent)
                        started?.let { put("process_started_ms",it) }
                        put("lock_ids",JsonArray(locks.map(::JsonPrimitive)))
                    }
                    val data=Base64.getEncoder().encodeToString(info.toString().toByteArray())
                    peer.respond(request,"""{"code":0,"stdout":"$data","stderr":"","truncated":false,"timed_out":false}"""); true
                }
                "terminal.read" -> { peer.respond(request,buildJsonObject { put("text",screen) }.toString()); true }
                else -> false
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
        try {
            client.connect()
            val resolver=SessionResolver(client)
            assertEquals(io.herdrmobile.core.workspace.AgentKind.CODEX,resolver.resolve(terminal).notStarted)
            absent=false
            assertNull(resolver.resolve(terminal).notStarted,"An empty candidate list does not prove absence")
            absent=true; started=null
            assertNull(resolver.resolve(terminal).notStarted,"An unknown TUI start time stays ambiguous")
            started=2000; locks=listOf("unresolved-thread")
            assertNull(resolver.resolve(terminal).notStarted,"An unmatched lock prevents an empty conversation claim")
            locks=emptyList(); screen="OpenAI Codex (v1)\n› submitted prompt\n› "
            assertNull(resolver.resolve(terminal).notStarted,"Submitted prompts must never show an empty chat")
            screen="OpenAI Codex (v1)\nWorked for 1s\n› "
            assertNull(resolver.resolve(terminal).notStarted)
        } finally { client.close(); scope.cancel() }
    }

    @Test fun freshThreadPeersRetentionNewThreadAndProcessChange():Unit=runBlocking {
        val terminal=Terminal(address="t1",state="live",pid=11,cwd="/project",foreground=Foreground(pid=11,name="codex",argv=listOf("codex")))
        val peer=FakeCoreTransport()
        var process="11:1:11:1"
        var rows=listOf("a" to 2000L)
        var peers=emptyList<Terminal>()
        var screen=""
        var fileReads=0
        peer.intercept={ request ->
            when(FakeCoreTransport.method(request)) {
                "machine.exec" -> {
                    val info=buildJsonObject {
                        put("process_key",process); put("process_started_ms",1000)
                        put("stored",buildJsonArray { for((id,created) in rows) add(buildJsonObject {
                            put("session_id",id); put("path","/fixture/$id.jsonl"); put("created_ms",created); put("same_cwd",true)
                        }) })
                    }
                    val data=Base64.getEncoder().encodeToString(info.toString().toByteArray())
                    peer.respond(request,"""{"code":0,"stdout":"$data","stderr":"","truncated":false,"timed_out":false}"""); true
                }
                "file.read" -> {
                    fileReads++
                    val id=request["params"].obj().text("path").substringAfterLast('/').substringBefore('.')
                    val text="""{"type":"event_msg","payload":{"type":"user_message","message":"prompt $id"}}
                        |{"type":"event_msg","payload":{"type":"agent_message","message":"ok"}}
                        |{"type":"event_msg","payload":{"type":"task_complete","last_agent_message":"ok"}}
                        |""".trimMargin()
                    peer.respond(request,buildJsonObject { put("text",text); put("first",1); put("last",3); put("lines",3) }.toString()); true
                }
                "terminal.read" -> { peer.respond(request,buildJsonObject { put("text",screen) }.toString()); true }
                "terminal.list" -> { peer.respond(request,buildJsonObject { put("terminals",HcJson.encodeToJsonElement(peers)) }.toString()); true }
                else -> false
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
        try {
            client.connect()
            val resolver=SessionResolver(client)
            peers=listOf(terminal.copy(address="t2",created_ms=500))
            assertNull(resolver.resolve(terminal).binding,"An older live Codex peer prevents fresh binding")
            peers=listOf(terminal.copy(address="t2",created_ms=500,state="finished",finished_ms=3000))
            assertNull(resolver.resolve(terminal).binding,"A now-finished peer was present when the thread began")
            peers=listOf(terminal.copy(address="remote/t2",created_ms=500))
            assertEquals("a",resolver.resolve(terminal).binding?.sessionId)
            val reads=fileReads
            peers=listOf(terminal.copy(address="t2",created_ms=500))
            assertEquals("a",resolver.resolve(terminal).binding?.sessionId,"Retain despite screen disappearing and later peer observation")
            assertEquals(reads,fileReads,"Retained binding avoids rereading candidate bodies")
            rows=listOf("b" to 3000L,"a" to 2000L)
            assertNull(resolver.resolve(terminal).binding,"A new thread invalidates retention; two fresh threads cannot select newest by cwd alone")
            screen="› prompt b\n\n• ok\n\nWorked for 1s\n› Ask Codex to do anything"
            assertEquals("b",resolver.resolve(terminal).binding?.sessionId)
            process="11:1:11:2"; screen=""
            assertNull(resolver.resolve(terminal).binding,"Process change clears retained ownership")
            assertEquals(listOf("b","a"),resolver.resolve(terminal,true).candidates.map { it.sessionId })
            rows=listOf("old" to 500L); peers=emptyList()
            assertNull(resolver.resolve(terminal).binding,"A sole older file is never fresh evidence")
            rows=listOf("a" to 2000L); peers=listOf(terminal.copy(address="t2",created_ms=5000))
            assertEquals("a",resolver.resolve(terminal).binding?.sessionId,"A peer created after the thread was absent at its creation")
        } finally { client.close(); scope.cancel() }
    }
}
