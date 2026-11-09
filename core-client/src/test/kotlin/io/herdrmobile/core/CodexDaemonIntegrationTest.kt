package io.herdrmobile.core

import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.workspace.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.*

/** Observes the existing real Codex fixture; never sends input, kills or removes it. */
@EnabledIfEnvironmentVariable(named="HC_IT",matches="1")
class CodexDaemonIntegrationTest {
    @Test fun existingCodexBecomesReady():Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=HcClient(TransportFactory { UnixTransport(IntegrationCore.ensureRunning()) },scope,autoReconnect=false)
        var source:TranscriptSource?=null
        try {
            client.connect()
            val terminal=client.list().find { it.id=="t23" }
            assumeTrue(terminal!=null && AgentDetector.detect(terminal)?.kind==AgentKind.CODEX,"Real Codex t23 fixture is unavailable")
            source=TranscriptSource(client,"t23",scope,pollIntervalMs=100)
            val ready=withTimeout(30000) { source.state.first { it is TranscriptState.Ready } } as TranscriptState.Ready
            assertEquals(AgentKind.CODEX,ready.binding.kind)
            assertFalse(ready.binding.confirmed)
            assertTrue(source.items.value.any { it is ChatItem.AssistantText })
            assertEquals("live",client.get("t23").state)
            println("herdr-mobile-it t23 Ready: ${ready.binding.sessionId}")
        } finally { source?.close(); client.close(); scope.cancel() }
    }
}
