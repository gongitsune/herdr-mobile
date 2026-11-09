package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.files.execRead
import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.*

/** Only synthetic agent state in a temporary directory; no real CLI or account is started. */
@EnabledIfEnvironmentVariable(named="HC_IT",matches="1")
class TranscriptNotStartedIntegrationTest {
    @Test fun emptyClaudeAndCodexBecomeReadyAfterFirstConversation():Unit=runBlocking {
        val socket=IntegrationCore.ensureRunning()
        for(kind in listOf(AgentKind.CLAUDE_CODE,AgentKind.CODEX)) {
            val directory=Files.createTempDirectory("herdr-mobile-not-started-")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { UnixTransport(socket) },scope,autoReconnect=false)
            var terminal:Terminal?=null
            var source:TranscriptSource?=null
            try {
                withTimeout(30000) {
                    val command=if(kind==AgentKind.CLAUDE_CODE) "claude" else "codex"
                    val root=directory.resolve("agent-state")
                    val script=directory.resolve(command)
                    Files.writeString(script,"""
                        import os,sys,json,time,sqlite3
                        from pathlib import Path
                        root=Path(sys.argv[1]); kind=sys.argv[2]; session='fixture-session'
                        (root/'sessions').mkdir(parents=True)
                        def row(value): return json.dumps(value)+'\n'
                        answer='Synthetic first answer verifies the empty chat can follow its first conversation without ambiguity.'
                        if kind=='claude':
                            pid=os.getpid()
                            start=Path('/proc/%d/stat'%pid).read_text().rsplit(')',1)[1].split()[19]
                            (root/'sessions'/('%d.json'%pid)).write_text(json.dumps(dict(pid=pid,procStart=start,cwd=os.getcwd(),sessionId=session)))
                            print('fixture-ready',flush=True)
                        else:
                            print('OpenAI Codex (v0.fixture)\nfixture-ready\n› Ask Codex to do anything',flush=True)
                        while not (root/'go').exists(): time.sleep(.02)
                        if kind=='claude':
                            project=root/'projects'/'fixture'; project.mkdir(parents=True,exist_ok=True)
                            (project/(session+'.jsonl')).write_text(
                                row(dict(type='user',uuid='u',parentUuid=None,sessionId=session,message=dict(content='Synthetic first prompt'))) +
                                row(dict(type='assistant',uuid='a',parentUuid='u',sessionId=session,message=dict(id='m',content=[dict(type='text',text=answer)]))))
                        else:
                            path=root/'sessions'/'rollout-fixture.jsonl'
                            path.write_text(
                                row(dict(type='session_meta',payload=dict(id=session))) +
                                row(dict(type='event_msg',payload=dict(type='user_message',message='Synthetic first prompt'))) +
                                row(dict(type='event_msg',payload=dict(type='agent_message',message=answer))) +
                                row(dict(type='event_msg',payload=dict(type='task_complete',last_agent_message=answer))))
                            db=sqlite3.connect(root/'state_5.sqlite')
                            db.execute('CREATE TABLE threads(id TEXT, rollout_path TEXT, cwd TEXT, archived INTEGER, agent_role TEXT, source TEXT, created_at INTEGER, created_at_ms INTEGER, updated_at INTEGER)')
                            now=int(time.time()*1000)
                            db.execute('INSERT INTO threads VALUES(?,?,?,0,NULL,?,?,?,?)',(session,str(path),os.getcwd(),'cli',now//1000,now,now//1000))
                            db.commit(); db.close()
                            print('› Synthetic first prompt\n• '+answer+'\nWorked for 1s\n› ',flush=True)
                        while True: time.sleep(1)
                    """.trimIndent())
                    client.connect()
                    terminal=HcJson.decodeFromJsonElement(client.call("terminal.run",buildJsonObject {
                        put("argv",JsonArray(listOf("python3",script.toString(),root.toString(),command).map(::JsonPrimitive)))
                        put("cwd",directory.toString())
                        put("env",buildJsonObject {
                            put("HOME",directory.toString()); put("CLAUDE_CONFIG_DIR",root.toString()); put("CODEX_HOME",root.toString())
                        })
                        put("labels",buildJsonObject { put("herdr-mobile-test","1") })
                    }))
                    val id=terminal!!.id
                    while("fixture-ready" !in client.read(id)) delay(20)
                    source=TranscriptSource(client,id,scope,pollIntervalMs=50)
                    assertEquals(TranscriptState.NotStarted(kind),source!!.state.first { it is TranscriptState.NotStarted })
                    assertTrue(source!!.items.value.isEmpty())
                    if(kind==AgentKind.CLAUDE_CODE) {
                        val project=root.resolve("projects/fixture")
                        Files.createDirectories(project)
                        Files.writeString(project.resolve("fixture-session.jsonl"),"""{"type":"system","sessionId":"fixture-session"}"""+"\n")
                        source!!.refresh()
                        assertEquals(TranscriptState.NotStarted(kind),source!!.state.value)
                    }
                    Files.writeString(root.resolve("go"),"")
                    source!!.nudge()
                    val ready=source!!.state.first { it is TranscriptState.Ready } as TranscriptState.Ready
                    assertEquals(kind,ready.binding.kind)
                    assertFalse(ready.binding.confirmed)
                    assertEquals("Synthetic first prompt",source!!.items.value.filterIsInstance<ChatItem.UserMessage>().single().text)
                    assertTrue(source!!.items.value.any { it is ChatItem.AssistantText })
                    val previous=source!!.items.value
                    source!!.close()
                    source=TranscriptSource(client,id,scope)
                    assertEquals(ready,source!!.state.value,"A reopened real-core transcript is immediately Ready")
                    assertSame(previous,source!!.items.value)
                    client.close()
                    delay(50)
                    assertEquals(ready,source!!.state.value,"Disconnect must keep the conversation visible")
                    assertSame(previous,source!!.items.value)
                    val append=if(kind==AgentKind.CLAUDE_CODE)
                        """{"type":"assistant","uuid":"a2","parentUuid":"a","sessionId":"fixture-session","message":{"id":"m2","content":[{"type":"text","text":"After reconnect"}]}}"""
                    else """{"type":"event_msg","payload":{"type":"agent_message","message":"After reconnect"}}"""
                    Files.writeString(Path.of(ready.binding.path),append+"\n",StandardOpenOption.APPEND)
                    client.connect()
                    val updated=source!!.items.first { items -> items.any { it is ChatItem.AssistantText && it.markdown=="After reconnect" } }
                    assertEquals(1,updated.filterIsInstance<ChatItem.UserMessage>().size)
                    if(kind==AgentKind.CODEX) {
                        val tail=client.execRead(listOf("python3","-c",CODEX_TAIL_SCRIPT,ready.binding.path))
                        assertEquals(0,tail.code)
                        assertFalse(tail.truncated)
                        assertTrue(tail.stdout.size<=CODEX_TAIL_BYTES)
                        assertContains(tail.stdout.decodeToString(),"After reconnect")
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    source?.close()
                    try {
                        terminal?.let {
                            try { withTimeout(10000) { client.kill(it.id) } }
                            finally { withTimeout(10000) { client.rm(it.id) } }
                        }
                    } finally {
                        client.close(); scope.cancel()
                        Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                    }
                }
            }
        }
    }
}
