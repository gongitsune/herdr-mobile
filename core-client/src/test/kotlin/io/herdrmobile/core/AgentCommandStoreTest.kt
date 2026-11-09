package io.herdrmobile.core

import io.herdrmobile.core.usage.*
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class AgentCommandStoreTest {
    private fun FakeCoreTransport.reply(request: JsonObject, text: String, truncated: Boolean = false) {
        val encoded = Base64.getEncoder().encodeToString(text.toByteArray())
        respond(request, """{"code":0,"stdout":"$encoded","stderr":"","truncated":$truncated,"timed_out":false}""")
    }

    @Test fun firstReferenceFetchesAndThrottlesSeparatelyPerAgentMachineAndCwd(): Unit = runBlocking {
        withTimeout(8000) {
            val peer = FakeCoreTransport()
            val calls = AtomicInteger()
            peer.intercept = { request ->
                if (FakeCoreTransport.method(request) == "machine.exec") {
                    calls.incrementAndGet()
                    peer.reply(request, """{"commands":[{"name":"fixture","description":"From CLI"}]}""")
                    true
                } else false
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = HcClient(TransportFactory { peer }, scope, autoReconnect = false)
            val store = AgentCommandStore(client, scope)
            try {
                client.connect()
                val flow = store.commands(AgentKind.CLAUDE_CODE, null, "/first")
                assertNotNull(flow.first { it != null })
                coroutineScope {
                    repeat(10) { launch { store.refresh(AgentKind.CLAUDE_CODE, null, "/first") } }
                }
                assertEquals(1, calls.get())
                assertEquals(flow.value, store.commands(AgentKind.CLAUDE_CODE, null, "/first").value)
                assertNotNull(store.commands(AgentKind.CLAUDE_CODE, "remote", "/first").first { it != null })
                assertNotNull(store.commands(AgentKind.CLAUDE_CODE, null, "/second").first { it != null })
                assertNotNull(store.commands(AgentKind.CODEX, null, "/first").first { it != null })
                assertEquals(4, calls.get())
                val requests = peer.requests.filter { FakeCoreTransport.method(it) == "machine.exec" }.map { it["params"]!!.jsonObject }
                assertEquals(listOf(null, "remote", null, null), requests.map { it["on"]?.jsonPrimitive?.content })
                assertEquals(listOf("/first", "/first", "/second", "/first"), requests.map { it["cwd"]!!.jsonPrimitive.content })
                assertTrue(requests.all { it["timeout"]!!.jsonPrimitive.int == 20 })
                assertEquals(listOf("commands", "/first"), requests.first()["argv"]!!.jsonArray.takeLast(2).map { it.jsonPrimitive.content })
                assertEquals("/first", requests.last()["argv"]!!.jsonArray.last().jsonPrimitive.content)
                store.refresh(AgentKind.CLAUDE_CODE, null, "/first", force = true)
                assertEquals(5, calls.get())
                assertEquals(emptyList(), store.commands(AgentKind.OTHER, null, "/first").first { it != null })
                assertEquals(5, calls.get())
                store.close()
                store.refresh(AgentKind.CODEX, null, "/first", force = true)
                assertEquals(5, calls.get())
            } finally { store.close(); client.close(); scope.cancel() }
        }
    }

    @Test fun failuresPublishStaticFallbackThenRetainSuccessfulSnapshot(): Unit = runBlocking {
        withTimeout(8000) {
            val peer = FakeCoreTransport()
            val calls = AtomicInteger()
            val response = AtomicReference("""{"commands":[],"error":"timeout"}""")
            val truncated = AtomicReference(false)
            peer.intercept = { request ->
                if (FakeCoreTransport.method(request) == "machine.exec") {
                    calls.incrementAndGet()
                    peer.reply(request, response.get(), truncated.get())
                    true
                } else false
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = HcClient(TransportFactory { peer }, scope, autoReconnect = false)
            val store = AgentCommandStore(client, scope)
            try {
                client.connect()
                val flow = store.commands(AgentKind.CODEX, null, "/cwd")
                assertEquals(CommandCatalog.builtins(AgentKind.CODEX), flow.first { it != null })
                store.refresh(AgentKind.CODEX, null, "/cwd")
                assertEquals(1, calls.get(), "Failures must also be throttled")
                response.set("""{"commands":[{"name":"fixture","description":"Skill"}]}""")
                store.refresh(AgentKind.CODEX, null, "/cwd", force = true)
                val previous = flow.value!!
                assertEquals('$', previous.single { it.name == "fixture" }.trigger)
                for (failure in listOf("not JSON", "{}", """{"commands":[],"error":"request_failed"}""")) {
                    response.set(failure)
                    store.refresh(AgentKind.CODEX, null, "/cwd", force = true)
                    assertSame(previous, flow.value)
                }
                response.set("""{"commands":[]}""")
                truncated.set(true)
                store.refresh(AgentKind.CODEX, null, "/cwd", force = true)
                assertSame(previous, flow.value)
                truncated.set(false)
                store.refresh(AgentKind.CODEX, null, "/cwd", force = true)
                assertTrue(flow.value!!.none { it.name == "fixture" }, "A successful empty response removes stale skills")
            } finally { store.close(); client.close(); scope.cancel() }
        }
    }

    @Test fun closeCancelsPendingRefreshAndPreventsNewRequests(): Unit = runBlocking {
        withTimeout(8000) {
            val peer = FakeCoreTransport()
            val pending = CompletableDeferred<Unit>()
            peer.intercept = { request ->
                if (FakeCoreTransport.method(request) == "machine.exec") { pending.complete(Unit); true } else false
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = HcClient(TransportFactory { peer }, scope, autoReconnect = false)
            val store = AgentCommandStore(client, scope)
            try {
                client.connect()
                val flow = store.commands(AgentKind.CLAUDE_CODE, null, "/cwd")
                pending.await()
                val refresh = async { runCatching { store.refresh(AgentKind.CLAUDE_CODE, null, "/cwd", force = true) } }
                store.close()
                refresh.await()
                assertNull(flow.value)
                store.commands(AgentKind.CODEX, "remote", "/new")
                store.refresh(AgentKind.CODEX, "remote", "/new", force = true)
                assertEquals(1, peer.requests.count { FakeCoreTransport.method(it) == "machine.exec" })
            } finally { store.close(); client.close(); scope.cancel() }
        }
    }
}
