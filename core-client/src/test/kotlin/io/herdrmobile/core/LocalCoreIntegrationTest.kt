package io.herdrmobile.core

import io.herdrmobile.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "HC_IT", matches = "1")
class LocalCoreIntegrationTest {
    @Test fun runAttachOutputAndCleanup(): Unit = runBlocking {
        val path = IntegrationCore.ensureRunning()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client = HcClient(TransportFactory { UnixTransport(path) },scope)
        var created: Terminal? = null
        try {
            withTimeout(30000) {
                client.connect()
                val store = TerminalStore(client,scope)
                val terminal = client.run(argv=listOf("/bin/cat"), labels=mapOf("herdr-mobile-test" to "1"))
                created = terminal
                assertEquals("1",terminal.labels["herdr-mobile-test"])
                store.terminals.first { list -> list.any { it.id == terminal.id } }
                val session = client.attach(terminal.id,80,24)
                val token = "herdr-mobile-" + System.nanoTime()
                val output = async {
                    val text = StringBuilder()
                    session.output.first { text.append(it.decodeToString()); token in text }
                    text.toString()
                }
                session.write((token+"\n").toByteArray())
                assertContains(output.await(),token)
                assertContains(client.read(terminal.id),token)
                session.resize(90,30)
                session.focus(true)
                session.detach()
                session.closed.await()
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    created?.let {
                        // Only the terminal returned by this test's labeled run may be touched.
                        try { withTimeout(10000) { client.kill(it.id) } }
                        finally { withTimeout(10000) { client.rm(it.id) } }
                    }
                } finally {
                    client.close()
                    scope.cancel()
                }
            }
        }
    }
}
