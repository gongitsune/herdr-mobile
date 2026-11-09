package io.herdrmobile.core

import io.herdrmobile.core.usage.ClaudeAccountStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ClaudeAccountStoreTest {
    @Test fun firstReferenceFetchesPerMachineAndFailuresRetainTheSnapshot(): Unit = runBlocking {
        withTimeout(6000) {
            val peer=FakeCoreTransport()
            val calls=AtomicInteger()
            val machines=mutableListOf<String?>()
            peer.intercept={ request ->
                if(FakeCoreTransport.method(request)=="machine.exec") {
                    calls.incrementAndGet()
                    machines+=request["params"]!!.jsonObject["on"]?.jsonPrimitive?.content
                    val response=if(calls.get()==4) """{"models":[{"value":"sonnet","displayName":"Sonnet"}],"error":"timeout"}"""
                        else if(calls.get()==2) """{"models":[],"error":"timeout"}"""
                        else """{"models":[{"value":"opus","displayName":"Opus"}],"limitsUnavailable":"no_plan_limits"}"""
                    val encoded=Base64.getEncoder().encodeToString(response.toByteArray())
                    peer.respond(request,"""{"code":0,"stdout":"$encoded","stderr":"","truncated":false,"timed_out":false}""")
                    true
                } else false
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
            val store=ClaudeAccountStore(client,scope)
            try {
                client.connect()
                val flow=store.account(null)
                val previous=flow.first { it!=null }
                assertEquals(1,calls.get())
                store.refresh(null)
                assertEquals(1,calls.get())
                store.refresh(null,force=true)
                assertSame(previous,flow.value)
                assertEquals(2,calls.get())
                store.refresh(null)
                assertEquals(2,calls.get())
                assertNotNull(store.account("remote").first { it!=null })
                assertEquals(listOf(null,null,"remote"),machines)
                store.refresh(null,force=true)
                assertEquals("sonnet",flow.value!!.models.single().value)
                assertNull(flow.value!!.limits)
                assertNull(flow.value!!.limitsUnavailable)
                assertEquals(4,calls.get())
                store.close()
                store.refresh(null,force=true)
                assertEquals(4,calls.get())
            } finally { store.close(); client.close(); scope.cancel() }
        }
    }
}
