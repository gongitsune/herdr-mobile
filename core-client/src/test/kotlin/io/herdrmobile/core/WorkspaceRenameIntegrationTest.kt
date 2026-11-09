package io.herdrmobile.core

import io.herdrmobile.core.workspace.WorkspaceStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.*

@EnabledIfEnvironmentVariable(named="HC_IT",matches="1")
class WorkspaceRenameIntegrationTest {
    @Test fun adoptRoundTripAndRestore():Unit=runBlocking {
        val socket=IntegrationCore.ensureRunning()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=HcClient(TransportFactory { UnixTransport(socket) },scope,autoReconnect=false)
        var store:WorkspaceStore?=null
        val ids=mutableListOf<String>()
        var restore:JsonElement?=null
        var version:JsonElement?=null
        suspend fun get()=client.call("doc.get",buildJsonObject { put("name","herdr-app") }).jsonObject
        try {
            withTimeout(20000) {
                client.connect()
                val before=get(); restore=before["value"] ?: JsonNull
                val spaceId="sp"+java.util.UUID.randomUUID().toString().replace("-","")
                val tabId="tb"+java.util.UUID.randomUUID().toString().replace("-","")
                repeat(2) {
                    ids+=client.run(argv=listOf("sh","-c","sleep 120"),cwd="/tmp",labels=mapOf(
                        "herdr-mobile-test" to "1","herdr.space" to spaceId,"herdr.tab" to tabId)).id
                }
                store=WorkspaceStore(client,scope)
                store!!.spaces.first { it.any { s -> s.id==spaceId && s.tabs.single().panes.size==2 } }
                try { store!!.renameSpace(spaceId,"adopted space") }
                finally { version=get().getValue("version") }
                try { store!!.renameTab(spaceId,tabId,"adopted tab") }
                finally { version=get().getValue("version") }
                val doc=get().getValue("value").jsonObject
                val space=doc.getValue("spaces").jsonArray.map { it.jsonObject }.single { it["id"]==JsonPrimitive(spaceId) }
                val tab=space.getValue("tabs").jsonArray.single().jsonObject
                assertEquals(JsonPrimitive("adopted space"),space["name"])
                assertEquals(JsonPrimitive("adopted tab"),tab["name"])
                for(id in ids) {
                    assertTrue(client.get(id).keep)
                    assertTrue(io.herdrmobile.core.workspace.containsTerminal(tab["root"],id))
                }
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    store?.close()
                    if(version!=null) withTimeout(10000) {
                        client.call("doc.set",buildJsonObject { put("name","herdr-app"); put("version",version!!); put("value",restore!!) })
                        assertEquals(restore,get()["value"])
                    }
                } finally {
                    try { for(id in ids) { try { client.kill(id) } finally { client.rm(id) } } }
                    finally { client.close(); scope.cancel() }
                }
            }
        }
    }

    @Test fun roundTripAndRestore():Unit=runBlocking {
        val socket=IntegrationCore.ensureRunning()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=HcClient(TransportFactory { UnixTransport(socket) },scope,autoReconnect=false)
        var store:WorkspaceStore?=null
        var restore:JsonElement?=null
        var writtenVersion:JsonElement?=null
        suspend fun get()=client.call("doc.get",buildJsonObject { put("name","herdr-app") }).jsonObject
        suspend fun set(value:JsonElement,version:JsonElement)=client.call("doc.set",buildJsonObject {
            put("name","herdr-app"); put("version",version); put("value",value)
        }).jsonObject
        try {
            withTimeout(15000) {
                client.connect()
                val before=get()
                restore=before["value"] ?: JsonNull
                val id="sp-mobile-test-${System.nanoTime()}"
                val tab="tb-mobile-test"
                val base=(restore as? JsonObject) ?: buildJsonObject { put("spaces",JsonArray(emptyList())) }
                val fixture=Json.parseToJsonElement("""{"id":"$id","name":"before","folder":"/tmp","unknown":17,"tabs":[{"id":"$tab","name":"before","root":{}}]}""")
                val value=JsonObject(base.toMutableMap().apply {
                    put("spaces",JsonArray((base["spaces"] as? JsonArray).orEmpty()+fixture))
                })
                writtenVersion=set(value,before.getValue("version")).getValue("version")
                store=WorkspaceStore(client,scope)
                store!!.spaces.first { it.any { s -> s.id==id } }
                store!!.renameSpace(id,"  integration space  ")
                writtenVersion=get().getValue("version")
                store!!.renameTab(id,tab," integration tab ")
                val after=get()
                writtenVersion=after.getValue("version")
                val expected=Json.parseToJsonElement(value.toString().replace("\"name\":\"before\"","\"name\":\"integration space\"").replace("\"id\":\"$tab\",\"name\":\"integration space\"","\"id\":\"$tab\",\"name\":\"integration tab\""))
                assertEquals(expected,after["value"])
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    store?.close()
                    if(writtenVersion!=null) withTimeout(10000) {
                        // CAS refuses to overwrite any concurrent writer during cleanup.
                        set(restore!!,writtenVersion!!)
                        assertEquals(restore,get()["value"])
                    }
                } finally { client.close(); scope.cancel() }
            }
        }
    }
}
