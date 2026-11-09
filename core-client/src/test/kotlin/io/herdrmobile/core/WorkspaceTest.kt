package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.workspace.*
import io.herdrmobile.core.wire.Frame
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WorkspaceTest {
    @Test fun unnamedTabsUseFinalPositionWithinEachSpace() {
        val document=HcJson.parseToJsonElement("""{"spaces":[{"id":"s","tabs":[{"id":"empty","name":""},{"id":"named","name":"Primary"},{"id":"missing"},{"id":"blank","name":" "}]},{"id":"other","tabs":[{"id":"first"}]}]}""")
        val terminals=listOf(
            Terminal(address="later",state="live",created_ms=2,name="Terminal name",title="Terminal title",
                labels=mapOf("herdr.space" to "s")),
            Terminal(address="earlier",state="live",created_ms=1,title="Earlier title",
                labels=mapOf("herdr.space" to "s","herdr.tab" to "adopted")),
            Terminal(address="shared",state="live",created_ms=3,
                labels=mapOf("herdr.space" to "s","herdr.tab" to "adopted")),
            Terminal(address="existing",state="live",name="Do not rename",
                labels=mapOf("herdr.space" to "s","herdr.tab" to "named")),
            Terminal(address="new-space",state="live",name="Shell",
                labels=mapOf("herdr.space" to "new")))
        val spaces=projectWorkspace(terminals,mapOf("" to document))
        val tabs=spaces.single { it.id=="s" }.tabs
        assertEquals(listOf("empty","named","missing","blank","adopted","terminal:later"),tabs.map { it.id })
        assertEquals(listOf("1","Primary","3","4","5","6"),tabs.map { it.name })
        assertEquals(listOf("earlier","shared"),tabs[4].panes.map { it.terminal.id })
        assertEquals("1",spaces.single { it.id=="other" }.tabs.single().name)
        assertEquals("1",spaces.single { it.id=="new" }.tabs.single().name)
    }

    @Test fun documentOrderLabelsChildrenAndMachinesAreProjectedWithoutDerivedSpaces() {
        val terminals=listOf(
            Terminal(address="t1",cwd="/project",labels=mapOf("herdr.space" to "s","herdr.tab" to "tab")),
            Terminal(address="child",parent="t1",cwd="/other",labels=mapOf("herdr.seen" to "0")),
            Terminal(address="orphan",state="live",cwd="/project",labels=mapOf("herdr.space" to "s","herdr.tab" to "extra")),
            Terminal(address="plain",cwd="/derived"),
            Terminal(address="remote/t1",cwd="/remote",labels=mapOf("herdr.space" to "s","herdr.tab" to "tab")))
        val document=HcJson.parseToJsonElement("""{"spaces":[{"id":"s","name":"Desktop","folder":"/project","tabs":[{"id":"tab","name":"Primary","root":{"id":"root","type":"row","children":[{"id":"pane","type":"block","content":{"terminal":"t1"}}]}}]}],"future":true}""")
        val spaces=projectWorkspace(terminals,linkedMapOf("" to document,"remote" to document))
        assertEquals(listOf("s","remote/s"),spaces.map { it.id })
        assertEquals("Desktop",spaces.first().name)
        assertEquals(listOf("t1"),spaces.first().tabs.first().panes.map { it.terminal.id })
        assertEquals(listOf("child"),spaces.first().tabs.first().panes.single().subAgents.map { it.terminal.id })
        assertEquals("pane",spaces.first().tabs.first().panes.first().id)
        assertEquals("orphan",spaces.first().tabs[1].panes.single().terminal.id)
        assertEquals("remote/t1",spaces[1].tabs.first().panes.single().terminal.id)
        assertEquals("remote",spaces[1].machine)
    }
    @Test fun labelsRequireSuccessfulDocumentAndLiveStateAndUseForegroundFolder() {
        val records=listOf(
            Terminal(address="plain",state="live",cwd="/plain"),
            Terminal(address="parked",state="live",labels=mapOf("herdr.space" to "", "herdr.tab" to "tab")),
            Terminal(address="done",state="finished",labels=mapOf("herdr.space" to "done")),
            Terminal(address="failed/t",state="live",labels=mapOf("herdr.space" to "hidden")),
            Terminal(address="ok/t",state="live",cwd="/fallback",foreground=Foreground(cwd="/repo/name"),
                labels=mapOf("herdr.space" to "new", "herdr.tab" to "tab")),
            Terminal(address="ok/other",created_ms=1,state="live",labels=mapOf("herdr.space" to "new")),
            Terminal(address="ok/another",created_ms=2,state="live",labels=mapOf("herdr.space" to "new")))
        assertTrue(projectWorkspace(records,emptyMap()).isEmpty())
        val space=projectWorkspace(records,mapOf("" to JsonNull,"ok" to JsonNull)).single()
        assertEquals("ok/new",space.id)
        assertEquals("name",space.name)
        assertEquals("/repo/name",space.cwd)
        assertEquals(3,space.tabs.size)
        assertEquals("ok/tab",space.tabs.first().id)
    }

    @Test fun descendantsAreFlatOrderedBoundedAndKeptChildrenOwnTheirDescendants() {
        fun terminal(id:String,parent:String?=null,space:String?=null,created:Long=0)=
            Terminal(address=id,parent=parent,state="live",created_ms=created,
                labels=space?.let { mapOf("herdr.space" to it) }.orEmpty())
        val records=listOf(
            terminal("remote/root",space="s"),
            terminal("remote/child","root",created=2),
            terminal("remote/grandchild","child",created=1),
            terminal("remote/kept","root","s"),
            terminal("remote/kept-child","kept"),
            terminal("a","b"),terminal("b","a"),terminal("missing","absent"),
            terminal("unplaced"),terminal("unplaced-child","unplaced")
        )+(1..33).map { terminal("remote/deep$it",if(it==1) "root" else "deep${it-1}",created=it+10L) }
        val panes=projectWorkspace(records,mapOf("remote" to JsonNull)).flatMap { it.tabs }.flatMap { it.panes }
        assertEquals(setOf("remote/root","remote/kept"),panes.map { it.terminal.id }.toSet())
        val root=panes.single { it.terminal.id=="remote/root" }
        assertEquals(listOf("remote/grandchild","remote/child"),root.subAgents.take(2).map { it.terminal.id })
        assertTrue(root.subAgents.any { it.terminal.id=="remote/deep32" })
        assertFalse(root.subAgents.any { it.terminal.id=="remote/deep33" })
        assertTrue(root.subAgents.all { it.subAgents.isEmpty() })
        assertEquals(listOf("remote/kept-child"),panes.single { it.terminal.id=="remote/kept" }.subAgents.map { it.terminal.id })
    }

    @Test fun documentPanesTakePriorityOverSubAgentLabels() {
        val document=HcJson.parseToJsonElement("""{"spaces":[{"id":"s","folder":"/repo","tabs":[{"id":"tab","root":{"children":[{"id":"parent","content":{"terminal":"root"}},{"id":"child-pane","content":{"terminal":"child"}}]}}]}]}""")
        val panes=projectWorkspace(listOf(Terminal(address="root"),Terminal(address="child",parent="root"),
            Terminal(address="grandchild",parent="child"),Terminal(address="sibling",parent="root")),
            mapOf("" to document)).single().tabs.single().panes
        assertEquals(listOf("root","child"),panes.map { it.terminal.id })
        assertEquals("child-pane",panes[1].id)
        assertEquals(listOf("sibling"),panes[0].subAgents.map { it.terminal.id })
        assertEquals(listOf("grandchild"),panes[1].subAgents.map { it.terminal.id })
    }

    @Test fun failedMachineReadSuppressesAdoptionAndRecoveryWithNullAllowsIt():Unit=runBlocking {
        withTimeout(5000) {
            val peer=FakeCoreTransport()
            var failRemote=true
            peer.intercept={ request ->
                when(FakeCoreTransport.method(request)) {
                    "events.subscribe" -> {
                        peer.respond(request,"""{"channel":2,"seq":0,"terminals":[{"address":"local","state":"live","labels":{"herdr.space":"s"}},{"address":"remote/t","state":"live","labels":{"herdr.space":"s"}}]}""")
                        true
                    }
                    "doc.get" -> {
                        if(request["params"]?.jsonObject?.get("on")?.jsonPrimitive?.content=="remote" && failRemote)
                            peer.send(Frame(0,0,("""{"id":${request["id"]},"error":{"code":-32000,"message":"unavailable","data":{"kind":"io"}}}""").toByteArray()))
                        else peer.respond(request,"""{"value":null}""")
                        true
                    }
                    else -> false
                }
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer },scope,false)
            val store=WorkspaceStore(client,scope)
            try {
                client.connect()
                store.error.first { it!=null }
                store.spaces.first { it.map { s -> s.id }==listOf("s") }
                failRemote=false
                store.refresh()
                store.spaces.first { it.map { s -> s.id }==listOf("s","remote/s") }
                assertNull(store.error.value)
                failRemote=true
                store.refresh()
                store.spaces.first { it.map { s -> s.id }==listOf("s") }
                assertNotNull(store.error.value)
                assertFalse(peer.saw("doc.set"))
            } finally { store.close(); client.close(); scope.cancel() }
        }
    }
    @Test fun staleWorkspaceSurvivesDisconnectAndSnapshotWaitThenUsesNewSnapshot():Unit=runBlocking {
        withTimeout(5000) {
            val peers=listOf(FakeCoreTransport(),FakeCoreTransport())
            val opens=AtomicInteger()
            val subscription=CompletableDeferred<JsonObject>()
            peers.forEachIndexed { index,peer -> peer.intercept={ request ->
                when(FakeCoreTransport.method(request)) {
                    "events.subscribe" -> {
                        if(index==1) subscription.complete(request)
                        else peer.respond(request,"""{"channel":2,"seq":0,"terminals":[{"address":"old","state":"live","argv":["codex"]},{"address":"remote/hidden","state":"live","labels":{"herdr.space":"hidden"}}]}""")
                        true
                    }
                    "doc.get" -> {
                        if(request["params"]?.jsonObject?.containsKey("on")==true)
                            peer.send(Frame(0,0,("""{"id":${request["id"]},"error":{"code":-32000,"message":"unavailable","data":{"kind":"io"}}}""").toByteArray()))
                        else peer.respond(request,"""{"value":{"spaces":[{"id":"s","tabs":[{"id":"tab","root":{"content":{"terminal":"${if(index==0) "old" else "new"}"}}}]}]}}""")
                        true
                    }
                    else -> false
                }
            } }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peers[opens.getAndIncrement()] },scope,false)
            val store=WorkspaceStore(client,scope)
            fun panes()=store.spaces.value.flatMap { it.tabs }.flatMap { it.panes }
            try {
                client.connect()
                store.error.first { it!=null }
                store.spaces.first { it.flatMap { s -> s.tabs }.flatMap { t -> t.panes }.any { p -> p.terminal.id=="old" } }
                client.close()
                client.state.first { it is ConnectionState.Disconnected }
                // Let both collectors process the disconnect, then check the retained projection.
                delay(100)
                assertEquals(listOf("old"),panes().map { it.terminal.id })
                assertNotNull(panes().single().agent)
                assertNotNull(store.error.value)
                val reconnect=async { client.connect() }
                val request=subscription.await()
                delay(100)
                assertEquals(listOf("old"),panes().map { it.terminal.id })
                assertEquals(listOf("s"),store.spaces.value.map { it.id })
                peers[1].respond(request,"""{"channel":2,"seq":0,"terminals":[{"address":"new","state":"live","argv":["codex"]}]}""")
                reconnect.await()
                store.spaces.first { it.flatMap { s -> s.tabs }.flatMap { t -> t.panes }.map { p -> p.terminal.id }==listOf("new") }
                assertNull(store.error.value)
                assertFalse(peers.any { it.saw("doc.set") })
            } finally { store.close(); client.close(); scope.cancel() }
        }
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
