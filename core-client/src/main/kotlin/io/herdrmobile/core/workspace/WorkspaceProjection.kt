package io.herdrmobile.core.workspace

import io.herdrmobile.core.model.Terminal
import kotlinx.serialization.json.*

internal fun machineOf(address: String): String? = address.substringBefore('/',"").takeIf { it.isNotEmpty() }
internal fun qualify(id: String, machine: String?): String = if(machine==null || id.startsWith("$machine/")) id else "$machine/$id"
private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

/** Pure projection: documents are read, never normalized back into desktop storage. */
internal fun projectWorkspace(terminals: List<Terminal>, documents: Map<String,JsonElement>): List<Space> {
    data class T(val id: String,val name: String,val panes: MutableList<Pane> = mutableListOf())
    data class S(val id:String,var name:String,var cwd:String,val machine:String?,val derived:Boolean,
                 val tabs:LinkedHashMap<String,T> = linkedMapOf())
    val spaces=linkedMapOf<String,S>()
    val records=terminals.associateBy { it.id }
    val placed=mutableMapOf<String,Pair<S,T>>()
    fun title(t:Terminal)=t.name?.takeIf { it.isNotBlank() } ?: t.title.takeIf { it.isNotBlank() } ?: AgentDetector.detect(t)?.program ?: t.id
    fun pane(id:String,terminal:Terminal)=Pane(id,terminal,AgentDetector.detect(terminal))
    for ((machineKey,value) in documents) {
        val machine=machineKey.ifEmpty { null }
        for (entry in ((value as? JsonObject)?.get("spaces") as? JsonArray).orEmpty()) {
            val doc=entry as? JsonObject ?: continue
            val rawId=doc.string("id").takeIf { it.isNotBlank() } ?: continue
            val id=qualify(rawId,machine)
            val folder=doc.string("folder")
            val space=S(id,doc.string("name").ifBlank { folder.trimEnd('/').substringAfterLast('/').ifBlank { rawId } },folder,machine,false)
            spaces[id]=space
            for (tabEntry in (doc["tabs"] as? JsonArray).orEmpty()) {
                val tabDoc=tabEntry as? JsonObject ?: continue
                val tabId=tabDoc.string("id").takeIf { it.isNotBlank() } ?: continue
                val tab=T(qualify(tabId,machine),tabDoc.string("name").ifBlank { tabId })
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
    val visiting=mutableSetOf<String>()
    fun place(terminal:Terminal): Pair<S,T> {
        placed[terminal.id]?.let { return it }
        val machine=machineOf(terminal.id)
        val labelSpace=terminal.labels["herdr.space"]?.takeIf { it.isNotBlank() }
        val labelTab=terminal.labels["herdr.tab"]?.takeIf { it.isNotBlank() }
        if(labelSpace==null && labelTab==null && visiting.add(terminal.id)) {
            val parent=terminal.parent?.let { records[qualify(it,machine)] }
            if(parent!=null && parent.id !in visiting) {
                val position=place(parent)
                position.second.panes+=pane(terminal.id,terminal)
                placed[terminal.id]=position
                visiting.remove(terminal.id)
                return position
            }
        }
        visiting.remove(terminal.id)
        val cwd=terminal.cwd
        val spaceId=qualify(labelSpace ?: "derived:$cwd",machine)
        val space=spaces.getOrPut(spaceId) { S(spaceId,cwd.trimEnd('/').substringAfterLast('/').ifBlank { "Terminals" },cwd,machine,labelSpace==null) }
        if(space.cwd.isEmpty()) space.cwd=cwd
        val tabId=qualify(labelTab ?: "terminal:${terminal.id}",machine)
        val tab=space.tabs.getOrPut(tabId) { T(tabId,title(terminal)) }
        tab.panes+=pane(terminal.id,terminal)
        return (space to tab).also { placed[terminal.id]=it }
    }
    terminals.sortedWith(compareBy<Terminal> { it.created_ms }.thenBy { it.id }).forEach { place(it) }
    return spaces.values.map { s -> Space(s.id,s.name,s.cwd,s.tabs.values.map { Tab(it.id,it.name,it.panes.toList()) },s.machine,s.derived) }
}
