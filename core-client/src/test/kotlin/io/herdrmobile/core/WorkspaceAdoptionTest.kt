package io.herdrmobile.core

import io.herdrmobile.core.workspace.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class WorkspaceAdoptionTest {
    @Test fun emptyRootPreservesTabAndFindsItAcrossSpaces() {
        for(root in listOf("null", "{\"type\":\"row\",\"children\":[]}")) {
            val doc=Json.parseToJsonElement("""{"spaces":[{"id":"sp1","tabs":[]},{"id":"sp2","tabs":[{"id":"tb1","name":"custom","future":{"x":1},"root":$root}]}]}""").jsonObject
            val result=adoptTerminal(doc,"sp1","tb1","t1","/work")
            val spaces=result.getValue("spaces").jsonArray
            assertEquals(doc.getValue("spaces").jsonArray[0],spaces[0])
            val before=doc.getValue("spaces").jsonArray[1].jsonObject.getValue("tabs").jsonArray.single().jsonObject
            val after=spaces[1].jsonObject.getValue("tabs").jsonArray.single().jsonObject
            assertEquals(before.filterKeys { it!="root" },after.filterKeys { it!="root" })
            assertTrue(containsTerminal(after["root"],"t1"))
        }
    }

    @Test fun desktopJsonFixture() {
        var doc=buildJsonObject { put("spaces",JsonArray(emptyList())) }
        doc=adoptTerminal(doc,"sp1","tb1","t1","/work")
        doc=adoptTerminal(doc,"sp1","tb1","t2","/ignored")
        doc=adoptTerminal(doc,"sp1",null,"t3","/ignored")
        val ids=linkedMapOf<String,String>()
        var sequence=0
        val normalized=Regex("(?:p|tb)[a-f0-9]{32}").replace(doc.toString()) { m ->
            ids.getOrPut(m.value) {
                (if(m.value.startsWith("tb")) "tb" else "p")+(++sequence).toString().padStart(32,'0')
            }
        }.replace("\"name\":\"\",\"folder\"","\"name\":\"space name\",\"folder\"")
            .replace("\"id\":\"tb1\",\"name\":\"\"","\"id\":\"tb1\",\"name\":\"tab name\"")
        assertEquals(javaClass.getResource("/workspace-adopt.json")!!.readText().trim(),normalized)
        assertEquals(doc,adoptTerminal(doc,"sp1","tb1","t1","/ignored"))
    }

    @Test fun splitPreservesDesktopFieldsAndWeights() {
        for(parent in listOf("row","column","stack")) for(size in listOf("null","{\"weight\":4.0}","{\"px\":120.0}")) {
            val doc=Json.parseToJsonElement("""{"future":17,"spaces":[{"id":"sp1","name":"s","folder":"/work","extra":true,"tabs":[{"id":"tb1","name":"t","future":false,"root":{"id":"root","type":"$parent","children":[{"id":"old","type":"block","title":"keep","size":$size,"content":{"mod":"files","props":{"x":42}}}]}}]}]}""").jsonObject
            val result=adoptTerminal(doc,"sp1","tb1","t2","/ignored")
            assertEquals(doc["future"],result["future"])
            val space=result["spaces"]!!.jsonArray.single().jsonObject
            val tab=space["tabs"]!!.jsonArray.single().jsonObject
            assertEquals(JsonPrimitive(true),space["extra"])
            assertEquals(JsonPrimitive(false),tab["future"])
            val root=tab["root"]!!.jsonObject
            val children=root["children"]!!.jsonArray
            val row=if(parent=="row") root else children.single().jsonObject
            val panes=row["children"]!!.jsonArray
            assertEquals(JsonPrimitive("keep"),panes[0].jsonObject["title"])
            assertEquals(JsonPrimitive("files"),panes[0].jsonObject["content"]!!.jsonObject["mod"])
            if(parent=="row") {
                val expected=if(size.contains("4.0")) 2f else 0.5f
                assertEquals(expected,panes[1].jsonObject["size"]!!.jsonObject["weight"]!!.jsonPrimitive.float)
                if(size.contains("px")) assertEquals(JsonPrimitive(120.0),panes[0].jsonObject["size"]!!.jsonObject["px"])
            } else {
                assertEquals(Json.parseToJsonElement(size),row["size"])
                assertFalse("size" in panes[0].jsonObject)
                assertTrue(Regex("s[0-9a-f]{32}").matches(row["id"]!!.jsonPrimitive.content))
            }
        }
    }

    @Test fun refusesChangedLabelsOrFailedKeep():Unit=runBlocking {
        for(changedLabels in listOf(false,true)) {
            val peer=FakeCoreTransport()
            val record="""{"address":"t1","state":"live","cwd":"/work","labels":{"herdr.space":"sp1","herdr.tab":"tb1"}}"""
            peer.intercept={ req -> when(FakeCoreTransport.method(req)) {
                "events.subscribe" -> { peer.respond(req,"""{"channel":2,"seq":0,"terminals":[$record]}"""); true }
                "doc.get" -> { peer.respond(req,"""{"value":null,"version":0}"""); true }
                "terminal.get" -> { peer.respond(req,if(changedLabels) record.replace("sp1","sp2") else record); true }
                "terminal.keep" -> {
                    peer.send(io.herdrmobile.core.wire.Frame(0,0,"""{"id":${req["id"]},"error":{"code":-32000,"message":"denied","data":{"kind":"denied"}}}""".toByteArray())); true
                }
                else -> false
            } }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
            val store=WorkspaceStore(client,scope)
            try { withTimeout(5000) {
                client.connect(); store.spaces.first { it.isNotEmpty() }
                val error=assertFailsWith<io.herdrmobile.core.rpc.HcError> { store.renameSpace("sp1","name") }
                assertEquals(if(changedLabels) "not_in_layout" else "denied",error.kind)
                assertFalse(peer.saw("doc.set"))
            } } finally { store.close(); client.close(); scope.cancel() }
        }
    }

    @Test fun adoptsMissingSpaceAndTabWithCasRetry():Unit=runBlocking {
        for(machine in listOf("","remote/")) for(renameTab in listOf(false,true)) for(labeledTab in listOf(false,true)) for(existingSpace in listOf(false,true)) for(folderSource in listOf("foreground","cwd","home")) {
            if(existingSpace && !renameTab) continue
            val peer=FakeCoreTransport(terminalId=machine+"t1")
            val tabLabel=if(labeledTab) ",\"herdr.tab\":\"tb1\"" else ""
            val cwd=if(folderSource=="cwd") "/work" else ""
            val foreground=if(folderSource=="foreground") "/work" else ""
            val record="""{"address":"${machine}t1","state":"live","cwd":"$cwd","foreground":{"cwd":"$foreground"},"labels":{"herdr.space":"sp1"$tabLabel}}"""
            val base=if(existingSpace) Json.parseToJsonElement("""{"spaces":[{"id":"sp1","name":"","folder":"/work","tabs":[],"extra":true}]}""") else JsonNull
            var document:JsonElement=base
            var writes=0
            var keeps=0
            peer.intercept={ req ->
                val params=req["params"]?.jsonObject
                when(FakeCoreTransport.method(req)) {
                    "events.subscribe" -> { peer.respond(req,"""{"channel":2,"seq":0,"terminals":[$record]}"""); true }
                    "machine.exec" -> {
                        assertEquals("printf '%s' \"${'$'}HOME\"",params!!["argv"]!!.jsonArray.last().jsonPrimitive.content)
                        assertEquals(machine.removeSuffix("/").ifEmpty { null },params["on"]?.jsonPrimitive?.content)
                        peer.respond(req,"""{"code":0,"stdout":"L3dvcms=","timed_out":false}"""); true
                    }
                    "terminal.get" -> { peer.respond(req,record); true }
                    "terminal.keep" -> { assertEquals(machine+"t1",params!!["terminal"]!!.jsonPrimitive.content); keeps++; peer.respond(req,"{}"); true }
                    "doc.get" -> { peer.respond(req,"""{"value":$document,"version":$writes}"""); true }
                    "doc.set" -> {
                        assertEquals(machine.removeSuffix("/").ifEmpty { null },params!!["on"]?.jsonPrimitive?.content)
                        writes++
                        if(writes==1) {
                            document=JsonObject((base as? JsonObject).orEmpty().toMutableMap().apply { put("future",JsonPrimitive(42)) })
                            peer.send(io.herdrmobile.core.wire.Frame(0,0,"""{"id":${req["id"]},"error":{"code":-32000,"message":"conflict","data":{"kind":"conflict"}}}""".toByteArray()))
                        } else { document=params.getValue("value"); peer.respond(req,"""{"version":$writes}""") }
                        true
                    }
                    else -> false
                }
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val client=HcClient(TransportFactory { peer },scope,autoReconnect=false)
            val store=WorkspaceStore(client,scope)
            try { withTimeout(5000) {
                client.connect()
                store.spaces.first { it.any { s -> s.id==machine+"sp1" } }
                assertTrue(store.canRename(machine+"sp1"))
                if(renameTab) store.renameTab(machine+"sp1",(if(labeledTab) machine+"tb1" else machine+"terminal:"+machine+"t1")," tab ") else store.renameSpace(machine+"sp1"," space ")
                val space=document.jsonObject["spaces"]!!.jsonArray.single().jsonObject
                val tab=space["tabs"]!!.jsonArray.single().jsonObject
                assertEquals("/work",space["folder"]!!.jsonPrimitive.content)
                assertEquals(if(renameTab) "tab" else "",tab["name"]!!.jsonPrimitive.content)
                assertEquals(if(renameTab) "" else "space",space["name"]!!.jsonPrimitive.content)
                assertTrue(containsTerminal(tab["root"],"t1"))
                assertEquals(JsonPrimitive(42),document.jsonObject["future"])
                assertEquals(2,writes); assertEquals(2,keeps)
                if(existingSpace) assertEquals(JsonPrimitive(true),space["extra"])
                if(!labeledTab) assertTrue(Regex("tb[0-9a-f]{32}").matches(tab.getValue("id").jsonPrimitive.content))
            } } finally { store.close(); client.close(); scope.cancel() }
        }
    }
}
