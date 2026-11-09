package io.herdrmobile.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.*

class GitBranchTest {
    @Test fun branchAndFailuresUseReadOnlyExecWithThreeSecondTimeout():Unit=runBlocking {
        val peer=FakeCoreTransport()
        var response=""
        peer.intercept={ request ->
            if(FakeCoreTransport.method(request)=="machine.exec") {
                peer.respond(request,response)
                true
            } else false
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
        fun result(code:Int=0,text:String="feature/mobile\n",timedOut:Boolean=false,truncated:Boolean=false):String {
            val b64=Base64.getEncoder().encodeToString(text.toByteArray())
            return """{"code":$code,"stdout":"$b64","stderr":"","truncated":$truncated,"timed_out":$timedOut}"""
        }
        try {
            client.connect()
            response=result()
            val cwd="/repo with spaces '; literal"
            assertEquals("feature/mobile",client.gitBranch(cwd,"remote"))
            val params=peer.requests.last()["params"]!!.jsonObject
            assertEquals(listOf("env","LC_ALL=C","LANGUAGE=","git","--no-optional-locks","symbolic-ref","--short","HEAD"),
                params["argv"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(3,params["timeout"]!!.jsonPrimitive.int)
            assertEquals(cwd,params["cwd"]!!.jsonPrimitive.content)
            assertEquals("remote",params["on"]!!.jsonPrimitive.content)
            for(failure in listOf(result(code=128),result(code=1),result(timedOut=true),
                result(truncated=true),result(text="\n"),"{}")) {
                response=failure
                assertNull(client.gitBranch(cwd))
            }
            assertFalse("on" in peer.requests.last()["params"]!!.jsonObject)
            client.close()
            assertNull(client.gitBranch(cwd))
        } finally { client.close(); scope.cancel() }
    }

    @Test fun cancellationIsRethrown():Unit=runBlocking {
        val peer=FakeCoreTransport()
        val requested=CompletableDeferred<Unit>()
        peer.intercept={ request ->
            if(FakeCoreTransport.method(request)=="machine.exec") {
                requested.complete(Unit)
                true // Keep the RPC suspended until its caller is cancelled.
            } else false
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
        try {
            client.connect()
            val failure=CompletableDeferred<Throwable?>()
            val job=launch {
                try { client.gitBranch("/repo"); failure.complete(null) }
                catch(e:Throwable) { failure.complete(e) }
            }
            withTimeout(5000) { requested.await() }
            job.cancelAndJoin()
            assertIs<CancellationException>(failure.await())
        } finally { client.close(); scope.cancel() }
    }
}
