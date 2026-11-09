package io.herdrmobile.core.workspace

import io.herdrmobile.core.model.Terminal
import kotlinx.serialization.json.*

internal fun machineOf(address: String): String? = address.substringBefore('/',"").takeIf { it.isNotEmpty() }
internal fun qualify(id: String, machine: String?): String = if(machine==null || id.startsWith("$machine/")) id else "$machine/$id"
private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

/** Pure projection: documents are read, never normalized back into desktop storage. */
internal fun projectWorkspace(terminals: List<Terminal>, documents: Map<String,JsonElement>): List<Space> {
    data class T(val id: String,val name: String,val panes: MutableList<Pane> = mutableListOf())
    data class S(val id:String,var name:String,var cwd:String,val machine:String?,val explicitName:String = "",
                 val tabs:LinkedHashMap<String,T> = linkedMapOf())
    val spaces=linkedMapOf<String,S>()
    val records=terminals.associateBy { it.id }
    val placed=mutableMapOf<String,Pair<S,T>>()
    fun pane(id:String,terminal:Terminal)=Pane(id,terminal,AgentDetector.detect(terminal))
    for ((machineKey,value) in documents) {
        val machine=machineKey.ifEmpty { null }
        for (entry in ((value as? JsonObject)?.get("spaces") as? JsonArray).orEmpty()) {
            val doc=entry as? JsonObject ?: continue
            val rawId=doc.string("id").takeIf { it.isNotBlank() } ?: continue
            val id=qualify(rawId,machine)
            val folder=doc.string("folder")
            val space=S(id,doc.string("name").ifBlank { folder.trimEnd('/').substringAfterLast('/').ifBlank { rawId } },folder,machine,doc.string("name"))
            spaces[id]=space
            for (tabEntry in (doc["tabs"] as? JsonArray).orEmpty()) {
                val tabDoc=tabEntry as? JsonObject ?: continue
                val tabId=tabDoc.string("id").takeIf { it.isNotBlank() } ?: continue
                val tab=T(qualify(tabId,machine),tabDoc.string("name"))
                space.tabs[tab.id]=tab
                fun walk(value:JsonElement?) {
                    val node=value as? JsonObject ?: return
                    val content=node["content"] as? JsonObject
                    val terminalId=content?.string("terminal")?.takeIf { it.isNotEmpty() }?.let { qualify(it,machine) }
                    val terminal=records[terminalId]
                    if(terminal!=null && terminal.id !in placed) {
                        tab.panes+=pane(node.string("id").takeIf { it.isNotBlank() }?.let { qualify(it,machine) } ?: terminal.id,terminal)
                        placed[terminal.id]=space to tab
                    }
                    (node["children"] as? JsonArray)?.forEach { walk(it) }
                    (node["floating"] as? JsonArray)?.forEach { walk(it) }
                }
                walk(tabDoc["root"])
            }
        }
    }
    val ordered=terminals.sortedWith(compareBy<Terminal> { it.created_ms }.thenBy { it.id })
    for (terminal in ordered) {
        if (terminal.id in placed || terminal.state != "live") continue
        val machine=machineOf(terminal.id)
        // A present key means doc.get succeeded, even when its value is JsonNull.
        if ((machine ?: "") !in documents) continue
        val labelSpace=terminal.labels["herdr.space"]?.takeIf { it.isNotEmpty() } ?: continue
        val labelTab=terminal.labels["herdr.tab"]?.takeIf { it.isNotEmpty() }
        val cwd=terminal.foreground?.cwd ?: terminal.cwd
        val spaceId=qualify(labelSpace,machine)
        val space=spaces.getOrPut(spaceId) {
            S(spaceId,cwd.trimEnd('/').substringAfterLast('/').ifBlank { labelSpace },cwd,machine)
        }
        val tabId=qualify(labelTab ?: "terminal:${terminal.id}",machine)
        val tab=space.tabs.getOrPut(tabId) { T(tabId,"") }
        tab.panes+=pane(terminal.id,terminal)
        placed[terminal.id]=space to tab
    }
    val children=mutableMapOf<String,MutableList<Pane>>()
    for (terminal in ordered.filter { it.id !in placed && isSubAgent(it) }) {
        var current=terminal
        val visited=mutableSetOf(terminal.id)
        for (depth in 0 until 32) {
            val parent=current.parent?.takeIf { it.isNotEmpty() }
                ?.let { qualify(it,machineOf(current.id)) } ?: break
            if (!visited.add(parent)) break
            if (parent in placed) {
                children.getOrPut(parent) { mutableListOf() }+=pane(terminal.id,terminal)
                break
            }
            current=records[parent] ?: break
            // Kept descendants belong to their own pane, never to an earlier ancestor.
            if (!isSubAgent(current)) break
        }
    }
    return spaces.values.map { s ->
        Space(s.id,s.name,s.cwd,s.tabs.values.mapIndexed { index,tab ->
            Tab(tab.id,tab.name.ifBlank { (index+1).toString() },tab.panes.map { it.copy(subAgents=children[it.terminal.id].orEmpty()) },tab.name)
        },s.machine,s.explicitName)
    }
}

private fun isSubAgent(terminal: Terminal): Boolean =
    !terminal.parent.isNullOrEmpty() && terminal.labels["herdr.space"].isNullOrEmpty()
