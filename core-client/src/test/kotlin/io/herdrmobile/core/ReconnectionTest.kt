package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.rpc.HcError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.*

class ReconnectionTest {
    @Test fun reconnectResubscribesReplacesSnapshotAndNeverReplaysInput(): Unit = runBlocking {
        withTimeout(10000) {
            val peers = listOf(FakeCoreTransport("old",900),FakeCoreTransport("fresh",0),FakeCoreTransport("third",0))
            val attempts=AtomicInteger()
            val delays=ConcurrentLinkedQueue<Int>()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peers[attempts.getAndIncrement()] },scope,true) {
                delays.add(it); 250
            }
            try {
                val store=TerminalStore(client,scope)
                client.connect()
                store.terminals.first { it.singleOrNull()?.id == "old" }
                val attach=client.attach("old",80,24)
                peers[0].intercept = { FakeCoreTransport.method(it) == "terminal.send" }
                val pending=async { runCatching { client.send("old","do not replay") } }
                eventually { peers[0].saw("terminal.send") }
                peers[0].close()
                assertEquals("disconnected",attach.closed.await().reason)
                assertTrue(pending.await().isFailure)
                val retry=client.state.first { it is ConnectionState.Reconnecting } as ConnectionState.Reconnecting
                assertEquals(1,retry.attempt)
                assertEquals(250L,retry.nextDelayMs)
                store.staleTerminals.first { it.singleOrNull()?.id == "old" }
                assertTrue(store.terminals.value.isEmpty())
                store.terminals.first { it.singleOrNull()?.id == "fresh" }
                eventually { store.staleTerminals.value.isEmpty() }
                assertFalse(peers[1].saw("terminal.send"))
                assertFalse(peers[1].saw("terminal.attach"))
                peers[1].titleEvent(1,"seq reset accepted")
                store.terminals.first { it.singleOrNull()?.title == "seq reset accepted" }
                peers[1].close()
                store.terminals.first { it.singleOrNull()?.id == "third" }
                assertEquals(listOf(1,1),delays.toList()) // Success resets the backoff.
            } finally { client.close(); scope.cancel() }
        }
    }

    @Test fun reconnectNowBypassesDelayAndTracksIdenticalCoreMetadata(): Unit = runBlocking {
        withTimeout(5000) {
            val peers=listOf(FakeCoreTransport("old"),FakeCoreTransport("new"),FakeCoreTransport("newest"))
            val opens=AtomicInteger()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peers[opens.getAndIncrement()] },scope,true) { 30_000 }
            val store=TerminalStore(client,scope)
            try {
                client.connect()
                store.terminals.first { it.singleOrNull()?.id == "old" }
                peers[0].close()
                client.state.first { it is ConnectionState.Reconnecting }
                client.reconnectNow()
                store.terminals.first { it.singleOrNull()?.id == "new" }
                // Explicit replacement with equal Connected(core, features) must also resubscribe.
                client.reconnectNow()
                store.terminals.first { it.singleOrNull()?.id == "newest" }
                assertEquals(3,opens.get())
            } finally { client.close(); scope.cancel() }
        }
    }

    @Test fun autoToggleStopsPendingRetryAndClosePreventsResurrection(): Unit = runBlocking {
        withTimeout(5000) {
            val opens=AtomicInteger()
            val peer=FakeCoreTransport()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory {
                if (opens.incrementAndGet()==1) throw HcError("not_running","offline")
                peer
            },scope,true) { 100 }
            try {
                assertFailsWith<HcError> { client.connect() }
                client.state.first { it is ConnectionState.Reconnecting }
                client.autoReconnect=false
                assertIs<ConnectionState.Failed>(client.state.value)
                delay(150)
                assertEquals(1,opens.get())
                client.autoReconnect=true
                client.state.first { it is ConnectionState.Connected }
                assertEquals(2,opens.get())
                client.autoReconnect=false
                assertFalse(peer.closed.get()) // Disabling does not drop an established session.
                client.close()
                client.autoReconnect=true
                delay(150)
                assertEquals(2,opens.get())
                assertEquals(ConnectionState.Disconnected,client.state.value)
            } finally { client.close(); scope.cancel() }
        }
    }

    @Test fun permanentErrorsDoNotRetryButManualRetryIsAllowed(): Unit = runBlocking {
        for (kind in listOf("auth","host_key","unsupported")) {
            val count=AtomicInteger()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { count.incrementAndGet(); throw HcError(kind,"stop") },scope,true) { 1 }
            try {
                assertEquals(kind,assertFailsWith<HcError> { client.connect() }.kind)
                assertIs<ConnectionState.Failed>(client.state.value)
                client.autoReconnect=false
                client.autoReconnect=true
                delay(30)
                assertEquals(1,count.get())
                assertFailsWith<HcError> { client.reconnectNow() }
                assertEquals(2,count.get())
            } finally { client.close(); scope.cancel() }
        }
    }

    @Test fun repeatedFailuresIncreaseAttemptAndCloseCancelsTimer(): Unit = runBlocking {
        withTimeout(5000) {
            val count=AtomicInteger()
            val requested=ConcurrentLinkedQueue<Int>()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { count.incrementAndGet(); throw HcError("io","offline") },scope,true) {
                requested.add(it); 10
            }
            try {
                assertFailsWith<HcError> { client.connect() }
                eventually { requested.size >= 7 }
                assertEquals((1..7).toList(),requested.toList().take(7))
                client.close()
                val stopped=count.get()
                delay(50)
                assertEquals(stopped,count.get())
                assertEquals(ConnectionState.Disconnected,client.state.value)
            } finally { client.close(); scope.cancel() }
        }
    }

    @Test fun closingDuringHelloClosesTransportAndCancelsAttempt(): Unit = runBlocking {
        withTimeout(5000) {
            val peer=FakeCoreTransport(replyToHello=false)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val count=AtomicInteger()
            val client=HcClient(TransportFactory { count.incrementAndGet(); peer },scope,true) { 1 }
            try {
                val connecting=async { runCatching { client.connect() } }
                eventually { peer.saw("hello") }
                client.close()
                assertTrue(connecting.await().isFailure)
                assertTrue(peer.closed.get())
                delay(30)
                assertEquals(1,count.get())
                assertEquals(ConnectionState.Disconnected,client.state.value)
            } finally { client.close(); scope.cancel() }
        }
    }

    @Test fun disablingDuringReconnectHelloClosesTheInFlightStream(): Unit = runBlocking {
        withTimeout(5000) {
            val peers=listOf(FakeCoreTransport(),FakeCoreTransport(replyToHello=false),FakeCoreTransport("recovered"))
            val count=AtomicInteger()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peers[count.getAndIncrement()] },scope,true) { 10 }
            try {
                client.connect()
                peers[0].close()
                eventually { peers[1].saw("hello") }
                client.autoReconnect=false
                eventually { peers[1].closed.get() }
                assertIs<ConnectionState.Failed>(client.state.value)
                client.autoReconnect=true
                client.state.first { it is ConnectionState.Connected }
                assertEquals(3,count.get())
            } finally { client.close(); scope.cancel() }
        }
    }

    @Test fun explicitCloseCompletesAttachAsDisconnectedEvenWithoutCollector(): Unit = runBlocking {
        withTimeout(5000) {
            val peer=FakeCoreTransport()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
            try {
                client.connect()
                val attach=client.attach("t1",80,24)
                client.close()
                assertEquals(CloseReason("disconnected"),attach.closed.await())
                assertEquals(ConnectionState.Disconnected,client.state.value)
            } finally { client.close(); scope.cancel() }
        }
    }

    @Test fun jitterFollowsExponentialBaseAndThirtySecondCeiling() {
        val random=Random(0)
        val bases=listOf(1000L,2000L,4000L,8000L,16000L,30000L,30000L)
        bases.forEachIndexed { index,base ->
            val values=List(100) { reconnectDelayMs(index+1,random) }
            assertTrue(values.all { it in base*80/100..minOf(base*120/100,30000) })
            assertTrue(values.distinct().size>1)
        }
        assertTrue(reconnectDelayMs(Int.MAX_VALUE,random) in 24000..30000)
    }

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(3000) { while (!condition()) delay(1) }
    }
}
