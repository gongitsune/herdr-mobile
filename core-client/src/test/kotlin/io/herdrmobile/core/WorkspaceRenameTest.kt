package io.herdrmobile.core

import io.herdrmobile.core.workspace.WorkspaceStore
import io.herdrmobile.core.rpc.HcError
import io.herdrmobile.core.wire.Frame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class WorkspaceRenameTest {
    private val original=Json.parseToJsonElement("""{"future":[3,2,1],"spaces":[{"id":"sp1","name":"before","extra":{"a":false},"tabs":[{"id":"tb1","name":"old","root":{"unknown":42}},{"id":"tb2","name":"other"}]}]}""")

    private fun error(peer:FakeCoreTransport,request:JsonObject,kind:String) {
        peer.send(Frame(0,Frame.DATA,"""{"id":${request["id"]},"error":{"code":-32000,"message":"fixture","data":{"kind":"$kind"}}}""".toByteArray()))
    }

    @Test fun rawJsonCasRetryAndQualifiedIds():Unit=runBlocking {
        for(machine in listOf("","remote/")) {
            val peer=FakeCoreTransport(terminalId=machine+"t1")
            var document=original
            var version=7
            var writes=0
            peer.intercept={ request ->
                val params=request["params"]?.jsonObject
                when(FakeCoreTransport.method(request)) {
                    "doc.get" -> {
                        peer.respond(request,buildJsonObject { put("value",document); put("version",version) }.toString()); true
                    }
                    "doc.set" -> {
                        assertEquals(machine.removeSuffix("/").ifEmpty { null },params!!["on"]?.jsonPrimitive?.content)
                        assertEquals(version,params["version"]!!.jsonPrimitive.int)
                        writes++
                        if(writes==1) {
                            document=JsonObject(document.jsonObject.toMutableMap().apply { put("concurrent",JsonPrimitive("preserved")) })
                            version++
                            error(peer,request,"conflict")
                        } else {
                            document=params.getValue("value"); version++
                            peer.respond(request,"""{"version":$version}""")
                        }
                        true
                    }
                    else -> false
                }
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
            val store=WorkspaceStore(client,scope)
            try {
                withTimeout(5000) {
                    client.connect()
                    store.spaces.first { it.any { s -> s.id==machine+"sp1" } }
                    assertTrue(store.canRename(machine+"sp1"))
                    assertTrue(store.canRename(machine+"missing"))
                    val initial=store.spaces.value.first { it.id==machine+"sp1" }
                    assertEquals("before",initial.explicitName)
                    assertEquals("old",initial.tabs.first().explicitName)
                    store.renameSpace(machine+"sp1","  before  ")
                    store.renameTab(machine+"sp1",machine+"tb1"," old ")
                    assertEquals(0,writes)
                    store.renameSpace(machine+"sp1","  renamed  ")
                    store.renameTab(machine+"sp1",machine+"tb1","   ")
                    val expected=original.toString().replace("\"before\"","\"renamed\"").replace("\"old\"","\"\"")
                    assertEquals(JsonObject(Json.parseToJsonElement(expected).jsonObject.toMutableMap().apply { put("concurrent",JsonPrimitive("preserved")) }),document)
                    assertEquals(expected.dropLast(1)+",\"concurrent\":\"preserved\"}",document.toString())
                    assertEquals(listOf("future","spaces","concurrent"),document.jsonObject.keys.toList())
                    store.spaces.first { it.any { s -> s.id==machine+"sp1" && s.name=="renamed" } }
                    assertEquals("not_in_layout",assertFailsWith<HcError> { store.renameSpace(machine+"missing","x") }.kind)
                    assertEquals("not_in_layout",assertFailsWith<HcError> { store.renameTab(machine+"sp1",machine+"missing","x") }.kind)
                    store.renameTab(machine+"sp1",machine+"tb1","  ")
                    assertEquals(3,writes)
                    document=JsonNull
                    assertEquals("not_in_layout",assertFailsWith<HcError> { store.renameSpace(machine+"sp1","x") }.kind)
                    store.refresh()
                    assertTrue(store.canRename(machine+"sp1"))
                }
            } finally { store.close(); client.close(); scope.cancel() }
        }
    }

    @Test fun conflictLimitAndOtherErrors():Unit=runBlocking {
        for(kind in listOf("conflict","denied")) {
            val peer=FakeCoreTransport()
            var writes=0
            peer.intercept={ request -> when(FakeCoreTransport.method(request)) {
                "doc.get" -> { peer.respond(request,"""{"value":$original,"version":1}"""); true }
                "doc.set" -> { writes++; error(peer,request,kind); true }
                else -> false
            } }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
            val store=WorkspaceStore(client,scope)
            try {
                withTimeout(5000) {
                    client.connect()
                    assertEquals(kind,assertFailsWith<HcError> { store.renameSpace("sp1","x") }.kind)
                    assertEquals(if(kind=="conflict") 3 else 1,writes)
                }
            } finally { store.close(); client.close(); scope.cancel() }
        }
    }
}
