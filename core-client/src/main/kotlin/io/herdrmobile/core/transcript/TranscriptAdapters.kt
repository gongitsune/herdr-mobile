package io.herdrmobile.core.transcript

import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.workspace.AgentKind
import kotlinx.serialization.json.*

internal fun JsonElement?.obj():JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonElement?.arr():JsonArray = this as? JsonArray ?: JsonArray(emptyList())
internal fun JsonElement?.str():String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.text(key:String)=get(key).str()
internal fun JsonObject.flag(key:String)=(get(key) as? JsonPrimitive)?.booleanOrNull==true
internal fun contentText(value:JsonElement?):String = when(value) {
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonArray -> value.mapNotNull { block -> block.obj().let { b ->
        when(b.text("type")) { "text","Text","input_text","output_text" -> b.text("text"); else -> null }
    } }.joinToString("\n")
    else -> ""
}
internal fun parseLines(lines:List<String>):List<JsonObject> = lines.mapNotNull { runCatching { HcJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
internal fun projectTranscript(kind:AgentKind,lines:List<String>,blocked:Boolean):List<ChatItem> =
    when(kind) { AgentKind.CLAUDE_CODE -> projectClaude(parseLines(lines),blocked); AgentKind.CODEX -> projectCodex(parseLines(lines),blocked); else -> emptyList() }

private fun questions(id:String,input:JsonElement?):List<ChatItem.Question> = input.obj()["questions"].arr().mapIndexedNotNull { i,value ->
    val question=value.obj(); val prompt=question.text("question").ifBlank { question.text("title") }
    if(prompt.isBlank()) null else ChatItem.Question("$id/question/$i",prompt,question["options"].arr().mapNotNull { v ->
        val o=v.obj(); val label=if(v is JsonPrimitive) v.str() else o.text("label")
        label.takeIf { it.isNotBlank() }?.let { ChatOption(it,o.text("description").ifEmpty { null }) }
    })
}

/** Rebuild the current UUID branch; preserve parallel message blocks and matching tool results. */
private fun projectClaude(all:List<JsonObject>,blocked:Boolean):List<ChatItem> {
    val nodes=linkedMapOf<String,JsonObject>()
    all.filterNot { it.flag("isSidechain") || it.text("teamName").isNotEmpty() || it.text("agentId").isNotEmpty() }
        .forEach { if(it.text("uuid").isNotBlank()) nodes[it.text("uuid")]=it }
    val positions=nodes.keys.withIndex().associate { it.value to it.index }
    val parents=nodes.mapValues { it.value.text("parentUuid") }.toMutableMap()
    // Relink the preserved part after a compact boundary, without rewriting source records.
    nodes.forEach { (_,r) -> if(r.text("subtype")=="compact_boundary") {
        val meta=r["compactMetadata"].obj()
        val kept=meta["preservedMessages"].obj()
        val ids=kept["uuids"].arr().map { it.str() }
        val segment=meta["preservedSegment"].obj()
        val anchor=kept.text("anchorUuid").ifEmpty { segment.text("anchorUuid") }
        val chain=if(ids.isNotEmpty() && ids.all { it in nodes }) ids else listOf(segment.text("headUuid"),segment.text("tailUuid")).filter { it in nodes }.distinct()
        if(chain.isNotEmpty() && anchor.isNotEmpty()) {
            parents.filter { it.value==anchor && it.key !in chain }.keys.toList().forEach { parents[it]=chain.last() }
            chain.forEachIndexed { i,id -> parents[id]=if(i==0) anchor else chain[i-1] }
        }
    } }
    val eligible=nodes.filterValues { it.text("type")!="progress" && it.text("subtype")!="fork_briefing" }
    val terminals=eligible.keys.filter { id -> eligible.keys.none { parents[it]==id } }.asReversed()
    val terminal=terminals.firstOrNull { id ->
        var at=id; val seen=mutableSetOf<String>(); var message=false
        while(at.isNotEmpty() && seen.add(at)) { if(nodes[at]?.text("type") in setOf("user","assistant")) { message=true; break }; at=parents[at].orEmpty() }
        message
    } ?: eligible.keys.lastOrNull() ?: return emptyList()
    val selected=mutableSetOf<String>()
    var at=terminal
    while(at.isNotEmpty() && selected.add(at)) {
        val r=nodes[at] ?: break
        val parent=parents[at].orEmpty()
        at=if(r.text("subtype")=="compact_boundary" && parent.isEmpty()) {
            r.text("logicalParentUuid").takeIf { (positions[it] ?: Int.MAX_VALUE)<(positions[at] ?: 0) }
                ?: eligible.keys.lastOrNull { it !in selected && (positions[it] ?: 0)<(positions[at] ?: 0) }.orEmpty()
        } else parent
    }
    val messageIds=selected.mapNotNull { nodes[it]?.get("message").obj().text("id").takeIf { id -> id.isNotEmpty() } }.toSet()
    nodes.forEach { (id,r) -> if(r.text("type")=="assistant" && r["message"].obj().text("id") in messageIds) selected+=id }
    val toolIds=selected.flatMap { nodes[it]?.get("message").obj()["content"].arr().filter { b -> b.obj().text("type")=="tool_use" }.map { b -> b.obj().text("id") } }.toSet()
    nodes.forEach { (id,r) -> if(r["message"].obj()["content"].arr().any { it.obj().text("tool_use_id") in toolIds }) selected+=id }
    val output=linkedMapOf<String,ChatItem>(); val results=mutableMapOf<String,Pair<String,Boolean>>()
    val ordered=nodes.filterKeys { it in selected }.values
    ordered.forEach { r -> r["message"].obj()["content"].arr().forEach { value -> val b=value.obj()
        if(b.text("type")=="tool_result") results[b.text("tool_use_id")]=contentText(b["content"]) to (b.flag("is_error") || r.text("toolDenialKind").isNotEmpty())
    } }
    val boundary=ordered.filter { it.text("subtype")=="turn_duration" }.maxOfOrNull { positions[it.text("uuid")] ?: -1 } ?: -1
    val pending=ordered.filter { (positions[it.text("uuid")] ?: -1)>boundary }.flatMap { r ->
        r["message"].obj()["content"].arr().map { it.obj() }.filter { it.text("type")=="tool_use" && it.text("id") !in results }.map { it.text("id") }
    }.lastOrNull()
    ordered.forEach { r ->
        val uuid=r.text("uuid"); val type=r.text("type"); val message=r["message"].obj(); val content=message["content"]
        if(type=="user" && !r.flag("isMeta") && !r.flag("isCompactSummary")) {
            val text=contentText(content)
            if(text.isNotBlank() && !text.startsWith("[Request interrupted")) output[uuid]=ChatItem.UserMessage(uuid,text)
        }
        if(type=="assistant") content.arr().forEachIndexed { index,value ->
            val block=value.obj()
            val blockIndex=(r["apiBlockIndex"] as? JsonPrimitive)?.intOrNull
            val id=(message.text("id").ifBlank { uuid })+"/"+(blockIndex?.let { (it+index).toString() } ?: "$uuid/$index")
            when(block.text("type")) {
                "text" -> if(block.text("text").isNotBlank()) output[id]=ChatItem.AssistantText(id,block.text("text"))
                "thinking" -> if(block.text("thinking").isNotBlank()) output[id]=ChatItem.Thinking(id,block.text("thinking"))
                "tool_use" -> {
                    val call=block.text("id"); val result=results[call]; val name=block.text("name")
                    output[call]=ChatItem.ToolCall(call,name,block["input"].toString().take(4096),result?.first?.take(64*1024),
                        if(result==null) { if((positions[uuid] ?: 0)>boundary) ToolState.RUNNING else ToolState.FAILED } else if(result.second) ToolState.FAILED else ToolState.SUCCEEDED)
                    if(blocked && call==pending) {
                        if(name in setOf("AskUserQuestion","request_user_input")) questions(call,block["input"]).forEach { output[it.id]=it }
                        else output["$call/approval"]=ChatItem.Question("$call/approval","Waiting for approval: $name",emptyList(),true)
                    }
                }
            }
        }
        when(r.text("subtype")) {
            "turn_duration" -> output[uuid]=ChatItem.TurnMarker(uuid,"Turn complete")
            "compact_boundary" -> output[uuid]=ChatItem.TurnMarker(uuid,"Context compacted")
        }
    }
    return output.values.toList()
}

private fun projectCodex(records:List<JsonObject>,blocked:Boolean):List<ChatItem> {
    val header=records.firstOrNull { it.text("type")=="session_meta" }?.get("payload").obj()
    val paginated=header.text("history_mode")=="paginated"
    val out=linkedMapOf<String,ChatItem>(); val turnRows=mutableListOf<MutableSet<String>>()
    var currentTurn=""; var ordinal=-1L; var turnHasUser=false
    fun add(item:ChatItem) { out[item.id]=item; turnRows.lastOrNull()?.add(item.id) }
    records.forEachIndexed { line,r ->
        val sequence=(r["ordinal"] as? JsonPrimitive)?.longOrNull
        if(paginated && sequence!=null) { if(sequence<=ordinal) return@forEachIndexed; ordinal=sequence }
        val type=r.text("type"); val p=r["payload"].obj(); val event=if(type=="event_msg") p.text("type") else type
        val row="line:$line"
        when(event) {
            "task_started","turn_started" -> {
                currentTurn=p.text("turn_id").ifEmpty { row }; turnRows+=mutableSetOf<String>(); turnHasUser=false
                add(ChatItem.TurnMarker("$currentTurn/start","Turn started"))
            }
            "task_complete","turn_complete","turn_aborted" -> add(ChatItem.TurnMarker(p.text("turn_id").ifEmpty { currentTurn }+"/end",if(event=="turn_aborted") "Turn interrupted" else "Turn complete"))
            "thread_rolled_back" -> if(!paginated) {
                repeat(minOf((p["num_turns"] as? JsonPrimitive)?.intOrNull ?: 0,turnRows.size)) { turnRows.removeAt(turnRows.lastIndex).forEach { out.remove(it) } }
                currentTurn=""
            }
            "context_compacted" -> add(ChatItem.TurnMarker(row,"Context compacted"))
            "user_message" -> if(!paginated && p.text("message").isNotBlank()) {
                if(currentTurn.isEmpty() || turnHasUser) { currentTurn=row; turnRows+=mutableSetOf<String>() }
                turnHasUser=true; add(ChatItem.UserMessage(row,p.text("message")))
            }
            "agent_message" -> if(!paginated && p.text("message").isNotBlank()) add(ChatItem.AssistantText(row,p.text("message")))
            "agent_reasoning" -> if(!paginated && p.text("text").isNotBlank()) add(ChatItem.Thinking(row,p.text("text")))
            "item_completed" -> {
                val item=p["item"].obj(); val id=p.text("turn_id")+"/"+item.text("id").ifEmpty { row }
                if(paginated) when(item.text("type")) {
                    "UserMessage" -> contentText(item["content"]).takeIf { it.isNotBlank() }?.let { text ->
                        val visible=text.substringAfterLast("## My request for Codex:").trim()
                        val prefix="<send_user_message_question_reply>"; val suffix="</send_user_message_question_reply>"
                        if(visible.startsWith(prefix) && visible.endsWith(suffix)) {
                            val replies=runCatching { HcJson.parseToJsonElement(visible.removePrefix(prefix).removeSuffix(suffix)) }.getOrNull()
                            val list=if(replies is JsonArray) replies else JsonArray(listOfNotNull(replies))
                            list.forEach { reply ->
                                val answered=reply.obj().text("questionItemId")
                                val ids=runCatching { HcJson.parseToJsonElement(answered).arr() }.getOrNull()
                                val call=ids?.getOrNull(1).str(); val question=ids?.getOrNull(2).str()
                                out.keys.filter { key -> if(call.isNotEmpty()) key.endsWith("/$call/question/$question") else key.contains("/$answered/question/") }.toList().forEach { out.remove(it) }
                            }
                        } else add(ChatItem.UserMessage(id,visible))
                    }
                    "AgentMessage" -> {
                        contentText(item["content"]).takeIf { it.isNotBlank() }?.let { add(ChatItem.AssistantText(id,it)) }
                        if(blocked) questions(id,item).forEach(::add)
                    }
                    "Reasoning" -> item["summary_text"].arr().joinToString("\n") { it.str() }.takeIf { it.isNotBlank() }?.let { add(ChatItem.Thinking(id,it)) }
                    "ContextCompaction" -> add(ChatItem.TurnMarker(id,"Context compacted"))
                    "CommandExecution","FileChange","McpToolCall","WebSearch","DynamicToolCall","FunctionCallOutput","Extension" -> {
                        val status=when(item.text("status")) { "completed","success" -> ToolState.SUCCEEDED; "failed","declined" -> ToolState.FAILED; else -> ToolState.RUNNING }
                        val name=if(item.text("type")=="McpToolCall") item.text("server")+"."+item.text("tool") else item.text("type")
                        add(ChatItem.ToolCall(id,name,(item["command"] ?: item["arguments"] ?: item["changes"] ?: JsonNull).toString().take(4096),
                            (item.text("aggregated_output").ifEmpty { item.text("stdout") }.ifEmpty { contentText(item["result"].obj()["content"]) }).takeIf { it.isNotEmpty() }?.take(64*1024),status))
                    }
                }
            }
        }
        if(!paginated && type=="response_item") when(p.text("type")) {
            "function_call","custom_tool_call","local_shell_call" -> {
                val id=p.text("call_id").ifEmpty { row }; val name=p.text("name").ifEmpty { p.text("type") }
                val args=p["arguments"] ?: p["input"] ?: p["action"] ?: JsonNull
                add(ChatItem.ToolCall(id,name,args.str().ifEmpty { args.toString() }.take(4096)))
                if(blocked && name=="request_user_input") {
                    val input=runCatching { HcJson.parseToJsonElement(args.str()) }.getOrNull()
                    questions(id,input).forEach(::add)
                }
            }
            "function_call_output","custom_tool_call_output" -> {
                val id=p.text("call_id"); val call=out[id] as? ChatItem.ToolCall
                val result=contentText(p["output"])
                val code=Regex("(?:Exit code:|Process exited with code)\\s*(-?\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull()
                    ?: runCatching { (HcJson.parseToJsonElement(result).obj()["metadata"].obj()["exit_code"] as? JsonPrimitive)?.intOrNull }.getOrNull()
                if(call!=null) out[id]=call.copy(result=result.take(64*1024),state=if(code!=null && code!=0) ToolState.FAILED else ToolState.SUCCEEDED)
                out.keys.filter { it.startsWith("$id/question/") }.toList().forEach { out.remove(it) }
            }
        }
    }
    return out.values.toList()
}
