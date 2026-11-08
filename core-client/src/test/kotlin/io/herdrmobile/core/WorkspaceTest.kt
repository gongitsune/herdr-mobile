package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.workspace.*
import io.herdrmobile.core.wire.Frame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WorkspaceTest {
    @Test fun documentOrderLabelsDerivedChildrenAndMachinesAreProjectedWithoutWrites() {
        val terminals=listOf(
            Terminal(address="t1",cwd="/project",labels=mapOf("herdr.space" to "s","herdr.tab" to "tab")),
            Terminal(address="child",parent="t1",cwd="/other",labels=mapOf("herdr.seen" to "0")),
            Terminal(address="orphan",cwd="/project",labels=mapOf("herdr.space" to "s","herdr.tab" to "extra")),
            Terminal(address="plain",cwd="/derived"),
            Terminal(address="remote/t1",cwd="/remote",labels=mapOf("herdr.space" to "s","herdr.tab" to "tab")))
        val document=HcJson.parseToJsonElement("""{"spaces":[{"id":"s","name":"Desktop","folder":"/project","tabs":[{"id":"tab","name":"Primary","root":{"id":"root","type":"row","children":[{"id":"pane","type":"block","content":{"terminal":"t1"}}]}}]}],"future":true}""")
        val spaces=projectWorkspace(terminals,linkedMapOf("" to document,"remote" to document))
        assertEquals(listOf("s","remote/s","derived:/derived"),spaces.map { it.id })
        assertEquals("Desktop",spaces.first().name)
        assertEquals(listOf("t1","child"),spaces.first().tabs.first().panes.map { it.terminal.id })
        assertEquals("pane",spaces.first().tabs.first().panes.first().id)
        assertEquals("orphan",spaces.first().tabs[1].panes.single().terminal.id)
        assertEquals("remote/t1",spaces[1].tabs.first().panes.single().terminal.id)
        assertEquals("remote",spaces[1].machine)
        assertTrue(spaces.last().derived)
    }
    @Test fun parentCyclesDoNotLoopAndMissingLabelSpaceGetsCheckoutName() {
        val records=listOf(Terminal(address="a",parent="b",cwd="/x"),Terminal(address="b",parent="a",cwd="/x"),
            Terminal(address="c",cwd="/repo/name",labels=mapOf("herdr.space" to "new")))
        val spaces=projectWorkspace(records,emptyMap())
        assertEquals(3,spaces.flatMap { it.tabs }.flatMap { it.panes }.map { it.terminal.id }.distinct().size)
        assertEquals("name",spaces.first { it.id=="new" }.name)
    }
    @Test fun agentDetectionUnwrapsAndDoesNotTrustStaleStatusInShell() {
        fun agent(argv:List<String>,status:Status?=null)=AgentDetector.detect(Terminal(address="t",state="live",argv=argv,status=status))
        assertEquals(AgentKind.CLAUDE_CODE,agent(listOf("sh","-c","exec claude --resume abc"))!!.kind)
        assertEquals(AgentKind.CODEX,agent(listOf("npx","--yes","@openai/codex"))!!.kind)
        assertEquals(AgentKind.CLAUDE_CODE,agent(listOf("node","/opt/node_modules/@anthropic-ai/claude-code/cli.js"))!!.kind)
        assertEquals(AgentKind.OTHER,agent(listOf("custom-bot"),Status(state="working",app="custom"))!!.kind)
        assertNull(agent(listOf("bash"),Status(state="working",app="claude-code")))
        assertNull(agent(listOf("random-script.py"),Status(state="working",app="claude-code")))
        assertEquals(listOf("codex"),AgentDetector.unwrap(listOf("fish","-ic","command \$argv[2..]; exec \$argv[1] -i","fish","codex")))
        assertEquals("sh",AgentDetector.unwrap(listOf("sh","-c","echo secret; claude")).first())
    }
    @Test fun documentsAreReadAfterSubscriptionAndReloadOnDocMissedAndReconnect(): Unit = runBlocking {
        withTimeout(5000) {
            val reads=AtomicInteger(); val opens=AtomicInteger()
            val peers=listOf(FakeCoreTransport(),FakeCoreTransport())
            fun doc(name:String)="""{"value":{"spaces":[{"id":"s","name":"$name","folder":"/project","tabs":[]}]},"version":1}"""
            peers.forEachIndexed { i,peer -> peer.intercept={ request ->
                if(FakeCoreTransport.method(request)=="doc.get") {
                    assertTrue(peer.saw("events.subscribe"))
                    peer.respond(request,doc(if(i==0) "name-${reads.incrementAndGet()}" else "reconnected")); true
                } else false
            } }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peers[opens.getAndIncrement()] },scope,true) { 30_000 }
            val store=WorkspaceStore(client,scope)
            try {
                client.connect()
                store.spaces.first { it.firstOrNull()?.name=="name-1" }
                peers[0].send(Frame(2,0,"""{"method":"event","params":{"seq":1,"type":"doc","data":{"name":"herdr-app","version":2}}}
""".toByteArray()))
                store.spaces.first { it.firstOrNull()?.name=="name-2" }
                peers[0].send(Frame(2,0,"""{"method":"missed","params":{"seq":8,"terminals":[]}}
""".toByteArray()))
                store.spaces.first { it.firstOrNull()?.name=="name-3" }
                client.reconnectNow()
                store.spaces.first { it.firstOrNull()?.name=="reconnected" }
                assertFalse(peers.any { it.saw("doc.set") })
            } finally { store.close(); client.close(); scope.cancel() }
        }
    }
}
