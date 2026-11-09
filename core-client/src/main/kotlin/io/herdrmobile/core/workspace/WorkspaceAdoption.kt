package io.herdrmobile.core.workspace

import kotlinx.serialization.json.*
import java.util.UUID

private fun fresh(prefix:String)=prefix+UUID.randomUUID().toString().replace("-","")
private fun JsonObject.edit(key:String,value:JsonElement)=JsonObject(toMutableMap().apply { put(key,value) })
internal fun containsTerminal(node:JsonElement?,address:String):Boolean {
    val obj=node as? JsonObject ?: return false
    return (obj["content"] as? JsonObject)?.get("terminal")==JsonPrimitive(address) ||
        (obj["children"] as? JsonArray).orEmpty().any { containsTerminal(it,address) } ||
        (obj["floating"] as? JsonArray).orEmpty().any { containsTerminal(it,address) }
}
private fun row(id:String,children:List<JsonElement>)=buildJsonObject {
    put("id",id); put("type","row"); put("children",JsonArray(children))
}
private fun lastPane(node:JsonObject):JsonObject? {
    val content=node["content"] as? JsonObject
    var last=node.takeIf { node["type"]==JsonPrimitive("block") && content!=null && ("terminal" in content || "mod" in content) }
    for(child in (node["children"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) lastPane(child)?.let { last=it }
    return last
}
/** Mirrors Node::split(Row): reuse a row parent, otherwise wrap the last pane. */
private fun split(node:JsonObject,target:JsonObject,pane:JsonObject):JsonObject {
    if(node===target) {
        val old=JsonObject(node.toMutableMap().apply { remove("size") })
        var wrapper=row(fresh("s"),listOf(old,pane))
        node["size"]?.let { wrapper=wrapper.edit("size",it) }
        return wrapper
    }
    val children=(node["children"] as? JsonArray) ?: return node
    val index=children.indexOfFirst { it===target }
    if(index>=0 && node["type"]==JsonPrimitive("row")) {
        val size=target["size"] as? JsonObject
        val share=(size?.get("weight") as? JsonPrimitive)?.floatOrNull ?: 1f
        val half=buildJsonObject { put("weight",share/2) }
        return node.edit("children",JsonArray(children.toMutableList().apply {
            if(size?.containsKey("px")!=true) set(index,target.edit("size",half))
            add(index+1,pane.edit("size",half))
        }))
    }
    return node.edit("children",JsonArray(children.map { if(it is JsonObject) split(it,target,pane) else it }))
}

internal fun adoptTerminal(document:JsonObject,spaceId:String,tabId:String?,address:String,folder:String):JsonObject {
    val spaces=(document["spaces"] as? JsonArray).orEmpty()
    if(spaces.filterIsInstance<JsonObject>().any { s -> (s["tabs"] as? JsonArray).orEmpty().any { containsTerminal((it as? JsonObject)?.get("root"),address) } }) return document
    val pane=buildJsonObject {
        put("id",fresh("p")); put("type","block"); put("content",buildJsonObject { put("terminal",address) })
    }
    val id=tabId ?: fresh("tb")
    val newTab=buildJsonObject { put("id",id); put("name",""); put("root",row(id,listOf(pane))) }
    val space=spaces.filterIsInstance<JsonObject>().firstOrNull { s ->
        tabId!=null && (s["tabs"] as? JsonArray).orEmpty().any {
            (it as? JsonObject)?.get("id")==JsonPrimitive(tabId)
        }
    } ?: spaces.filterIsInstance<JsonObject>().firstOrNull { it["id"]==JsonPrimitive(spaceId) }
    val updated=if(space==null) buildJsonObject {
        put("id",spaceId); put("name",""); put("folder",folder); put("tabs",JsonArray(listOf(newTab)))
    } else {
        val tabs=(space["tabs"] as? JsonArray).orEmpty()
        val tab=tabs.filterIsInstance<JsonObject>().firstOrNull { it["id"]==JsonPrimitive(id) }
        val root=tab?.get("root") as? JsonObject
        val target=root?.let(::lastPane)
        val next=if(tab!=null && root!=null && target!=null) tab.edit("root",split(root,target,pane)) else tab?.edit("root",newTab.getValue("root")) ?: newTab
        space.edit("tabs",JsonArray(if(tab==null) tabs+next else tabs.map { if(it===tab) next else it }))
    }
    return document.edit("spaces",JsonArray(if(space==null) spaces+updated else spaces.map { if(it===space) updated else it }))
}
