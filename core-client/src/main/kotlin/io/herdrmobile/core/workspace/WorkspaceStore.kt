package io.herdrmobile.core.workspace

import io.herdrmobile.core.*
import io.herdrmobile.core.rpc.HcError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.Closeable

/** Read-only view of desktop organization. This class never calls doc.set or mutates labels. */
class WorkspaceStore(private val client: HcClient, scope: CoroutineScope) : Closeable {
    private val owned=CoroutineScope(scope.coroutineContext+SupervisorJob(scope.coroutineContext[Job]))
    private val terminals=TerminalStore(client,owned)
    private val documents=MutableStateFlow<Map<String,JsonElement>>(emptyMap())
    private val mutable=MutableStateFlow<List<Space>>(emptyList())
    val spaces: StateFlow<List<Space>> = mutable.asStateFlow()
    private val failure=MutableStateFlow<HcError?>(null)
    val error: StateFlow<HcError?> = failure.asStateFlow()
    private val readLock=Mutex()
    init {
        owned.launch {
            combine(terminals.terminals,documents) { records,docs -> projectWorkspace(records,docs) }
                .flowOn(Dispatchers.Default)
                .collect { mutable.value=it }
        }
        owned.launch {
            combine(terminals.snapshotConnection,terminals.documentEpoch,
                terminals.terminals.map { list -> list.mapNotNull { machineOf(it.id) }.toSet() }.distinctUntilChanged()) { conn,_,_ -> conn }
                .collectLatest { conn ->
                    if(conn==null) documents.value=emptyMap()
                    else refresh()
                }
        }
    }
    /** Refetches documents only after events.subscribe has installed its snapshot/receiver. */
    suspend fun refresh() = readLock.withLock {
        val conn=terminals.snapshotConnection.value ?: return@withLock
        if(conn !== client.connection) return@withLock
        val machines=listOf("")+terminals.terminals.value.mapNotNull { machineOf(it.id) }.distinct().sorted()
        val next=linkedMapOf<String,JsonElement>()
        var failed: HcError?=null
        for(machine in machines) {
            try {
                val result=conn.call("doc.get",buildJsonObject { put("name","herdr-app"); if(machine.isNotEmpty()) put("on",machine) }).jsonObject
                next[machine]=result["value"] ?: JsonNull
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { failed=connectionError(e); next[machine]=JsonNull }
        }
        if(conn === client.connection) { documents.value=next; failure.value=failed }
    }
    override fun close() { owned.cancel(); mutable.value=emptyList() }
}
