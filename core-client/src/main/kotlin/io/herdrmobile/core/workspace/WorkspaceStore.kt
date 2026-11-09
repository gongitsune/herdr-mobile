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
    internal val terminals=TerminalStore(client,owned)
    // Keys contain only successful reads; JsonNull is an available, empty document.
    private val documents=MutableStateFlow<Map<String,JsonElement>>(emptyMap())
    private val mutable=MutableStateFlow<List<Space>>(emptyList())
    val spaces: StateFlow<List<Space>> = mutable.asStateFlow()
    private val failure=MutableStateFlow<HcError?>(null)
    val error: StateFlow<HcError?> = failure.asStateFlow()
    private val readLock=Mutex()
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
