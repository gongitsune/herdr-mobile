package io.herdrmobile.core

import io.herdrmobile.core.usage.ClaudeAccountStore
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.*

@EnabledIfEnvironmentVariable(named="HC_IT",matches="1")
class ClaudeAccountIntegrationTest {
    @Test fun readsModelsWithoutPrompt(): Unit = runBlocking {
        val socket=IntegrationCore.ensureRunning()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=HcClient(TransportFactory { UnixTransport(socket) },scope,autoReconnect=false)
        val store=ClaudeAccountStore(client,scope)
        try {
            client.connect()
            store.refresh(null)
            val account=store.account(null).value
            assertNotNull(account,"Control exchange did not return an account")
            assumeTrue(account.limitsUnavailable!="program_missing","Claude is not installed")
            assertTrue(account.models.isNotEmpty(),"Initialize must return models")
            val previous=store.account(null).value
            store.refresh(null)
            assertSame(previous,store.account(null).value,"Five-minute throttle must retain the snapshot")
        } finally { store.close(); client.close(); scope.cancel() }
    }
}
