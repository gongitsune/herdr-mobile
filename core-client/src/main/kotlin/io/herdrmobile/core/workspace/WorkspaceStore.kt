package io.herdrmobile.core.workspace

import io.herdrmobile.core.*
import io.herdrmobile.core.rpc.HcError
import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.model.Terminal
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.Closeable

/** Desktop organization; only explicit Space/Tab renames write to its document. */
class WorkspaceStore(private val client: HcClient, scope: CoroutineScope) : Closeable {
    private val owned=CoroutineScope(scope.coroutineContext+SupervisorJob(scope.coroutineContext[Job]))
    internal val terminals=TerminalStore(client,owned)
    // Keys contain only successful reads; JsonNull is an available, empty document.
    private val documents=MutableStateFlow<Map<String,JsonElement>>(emptyMap())
    private val mutable=MutableStateFlow<List<Space>>(emptyList())
    val spaces: StateFlow<List<Space>> = mutable.asStateFlow()
    private val failure=MutableStateFlow<HcError?>(null)
    val error: StateFlow<HcError?> = failure.asStateFlow()
    private val readLock=Mutex()
    fun canRename(spaceId: String): Boolean {
        val machine=machineOf(spaceId).orEmpty()
        return machine in documents.value
    }

    suspend fun renameSpace(spaceId: String, name: String) = rename(spaceId,null,name)

    suspend fun renameTab(spaceId: String, tabId: String, name: String) = rename(spaceId,tabId,name)

    private suspend fun rename(spaceId: String, tabId: String?, name: String) {
        val machine=machineOf(spaceId).orEmpty()
        fun params() = buildJsonObject {
            put("name","herdr-app")
            if(machine.isNotEmpty()) put("on",machine)
        }
        // Pin the connection: a reconnect must never replay a write on a new connection.
        val conn=client.connection ?: throw HcError("disconnected","Not connected")
        repeat(3) { attempt ->
            val result=conn.call("doc.get",params()).jsonObject
            val version=result["version"] ?: throw HcError("protocol","Layout document has no version")
            val raw=result["value"] ?: JsonNull
            var value=when(raw) {
                JsonNull -> buildJsonObject { put("spaces",JsonArray(emptyList())) }
                is JsonObject -> raw
                else -> throw HcError("protocol","Invalid layout document")
            }
            if(tabId!=null && machineOf(tabId).orEmpty()!=machine) notInLayout()
            val projected=projectWorkspace(terminals.workspaceSnapshot.value.records,mapOf(machine to raw))
                .firstOrNull { it.id==spaceId }
            val existing=layoutSpace(value,localId(spaceId,machine))
            val explicitName=if(tabId==null) (existing?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
                else ((existing?.get("tabs") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    .firstOrNull { it["id"]==JsonPrimitive(localId(tabId,machine)) }
                    ?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val targetExists=if(tabId==null) existing!=null || projected!=null else
                (existing?.get("tabs") as? JsonArray).orEmpty().any {
                    (it as? JsonObject)?.get("id")==JsonPrimitive(localId(tabId,machine))
                } || projected?.tabs?.any { it.id==tabId }==true
            if(targetExists && name.trim()==explicitName) return
            val missing=existing==null || (tabId!=null &&
                (existing["tabs"] as? JsonArray).orEmpty().none { (it as? JsonObject)?.get("id")==JsonPrimitive(localId(tabId,machine)) })
            if(missing) {
                val target=projected ?: notInLayout()
                val tabs=if(tabId==null) target.tabs else listOf(target.tabs.firstOrNull { it.id==tabId } ?: notInLayout())
                for(tab in tabs) for(pane in tab.panes) {
                    // Recheck labels/state against the current record before adopting, as desktop does.
                    val record=conn.call("terminal.get",buildJsonObject { put("terminal",pane.terminal.id) })
                    val terminal=HcJson.decodeFromJsonElement<Terminal>(record)
                    if(terminal.state!="live" || terminal.labels["herdr.space"]!=localId(spaceId,machine) ||
                        terminal.labels["herdr.tab"].orEmpty()!=pane.terminal.labels["herdr.tab"].orEmpty()) notInLayout()
                    if(!terminal.keep) conn.call("terminal.keep",buildJsonObject { put("terminal",pane.terminal.id); put("keep",true) })
                    val folder=layoutSpace(value,localId(spaceId,machine))?.get("folder")?.jsonPrimitive?.content ?: terminal.foreground?.cwd?.takeIf { it.isNotEmpty() } ?: terminal.cwd.takeIf { it.isNotEmpty() }
                        ?: run {
                            val home=conn.call("machine.exec",buildJsonObject {
                                if(machine.isNotEmpty()) put("on",machine)
                                put("timeout",10)
                                put("argv",buildJsonArray { add("sh"); add("-c"); add("printf '%s' \"${'$'}HOME\"") })
                            }).jsonObject
                            if(home["code"]?.jsonPrimitive?.intOrNull!=0 || home["timed_out"]==JsonPrimitive(true))
                                throw HcError("io","Cannot read machine home")
                            Base64.getDecoder().decode(home.getValue("stdout").jsonPrimitive.content).decodeToString()
                                .takeIf { it.isNotEmpty() } ?: throw HcError("io","Machine home is empty")
                        }
                    value=adoptTerminal(value,localId(spaceId,machine),terminal.labels["herdr.tab"]?.takeIf { it.isNotEmpty() },
                        localId(pane.terminal.id,machine),folder)
                }
                // A label-less tab gets a new desktop ID, so rename the adopted tab by its terminal below.
            }
            val space=layoutSpace(value,localId(spaceId,machine)) ?: notInLayout()
            val replacement=if(tabId==null) space.withName(name.trim()) else {
                val tabs=space["tabs"] as? JsonArray ?: notInLayout()
                val id=localId(tabId,machine).let { wanted ->
                    if(tabs.any { (it as? JsonObject)?.get("id")==JsonPrimitive(wanted) }) wanted
                    else {
                        val address=projected?.tabs?.firstOrNull { it.id==tabId }?.panes?.firstOrNull()?.terminal?.id ?: notInLayout()
                        tabs.filterIsInstance<JsonObject>().firstOrNull { containsTerminal(it["root"],localId(address,machine)) }
                            ?.get("id")?.jsonPrimitive?.content ?: notInLayout()
                    }
                }
                if(tabs.none { (it as? JsonObject)?.get("id")==JsonPrimitive(id) }) notInLayout()
                JsonObject(space.toMutableMap().apply {
                    put("tabs",JsonArray(tabs.map { tab ->
                        if((tab as? JsonObject)?.get("id")==JsonPrimitive(id)) tab.withName(name.trim()) else tab
                    }))
                })
            }
            val updated=JsonObject(value.toMutableMap().apply {
                put("spaces",JsonArray(value.getValue("spaces").jsonArray.map { if(it===space) replacement else it }))
            })
            try {
                conn.call("doc.set",JsonObject(params().toMutableMap().apply {
                    put("version",version); put("value",updated)
                }))
            } catch(e:HcError) {
                if(e.kind=="conflict" && attempt<2) return@repeat
                throw e
            }
            refresh()
            return
        }
    }

    private fun localId(id:String,machine:String) = if(machine.isEmpty()) id else id.removePrefix("$machine/")
    private fun layoutSpace(value:JsonElement?,id:String):JsonObject? =
        ((value as? JsonObject)?.get("spaces") as? JsonArray)?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it["id"]==JsonPrimitive(id) }
    private fun JsonObject.withName(name:String) = JsonObject(toMutableMap().apply { put("name",JsonPrimitive(name)) })
    private fun notInLayout():Nothing = throw HcError("not_in_layout","Target is not in the desktop layout")
    init {
        owned.launch {
            combine(terminals.workspaceSnapshot,documents) { snapshot,docs ->
                projectWorkspace(snapshot.records,docs)
            }
                .flowOn(Dispatchers.Default)
                .collect { mutable.value=it }
        }
        owned.launch {
            terminals.workspaceSnapshot
                .map { Triple(it.connection,it.documentEpoch,machines(it)) }
                .distinctUntilChanged()
                .collectLatest { (conn,_,_) ->
                    if(conn!=null) refresh()
                }
        }
    }
    /** Refetches documents only after events.subscribe has installed its snapshot/receiver. */
    suspend fun refresh() = readLock.withLock {
        val snapshot=terminals.workspaceSnapshot.value
        val conn=snapshot.connection ?: return@withLock
        if(conn !== client.connection) return@withLock
        val machines=machines(snapshot)
        val next=linkedMapOf<String,JsonElement>()
        var failed: HcError?=null
        for(machine in machines) {
            try {
                val result=conn.call("doc.get",buildJsonObject { put("name","herdr-app"); if(machine.isNotEmpty()) put("on",machine) }).jsonObject
                next[machine]=result["value"] ?: JsonNull
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { failed=connectionError(e) }
        }
        val current=terminals.workspaceSnapshot.value
        if(conn === client.connection && conn === current.connection &&
            snapshot.documentEpoch == current.documentEpoch && machines == machines(current)) {
            // Publish the outcome before documents can cause a new spaces projection.
            failure.value=failed
            documents.value=next
        }
    }
    private fun machines(snapshot: TerminalStore.WorkspaceSnapshot) =
        listOf("")+snapshot.records.mapNotNull { machineOf(it.id) }.distinct().sorted()
    override fun close() { owned.cancel(); mutable.value=emptyList() }
}
