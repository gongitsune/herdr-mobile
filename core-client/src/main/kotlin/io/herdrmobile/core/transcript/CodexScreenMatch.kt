package io.herdrmobile.core.transcript

import java.text.Normalizer

internal data class CodexScreenTranscript(val path:String,val answers:List<String>,val latestPrompt:String?,val completed:Pair<String,String>?)
internal fun codexScreenTranscript(path:String,lines:List<String>):CodexScreenTranscript {
    val answers=mutableListOf<String>()
    var prompt:String?=null
    var answer=""
    var completed:Pair<String,String>?=null
    for(row in parseLines(lines)) {
        val p=row["payload"].obj()
        when {
            row.text("type")=="event_msg" -> when(p.text("type")) {
                "task_started" -> { prompt=null; answer="" }
                "user_message" -> { prompt=p.text("message"); answer="" }
                "item_completed" -> {
                    val item=p["item"].obj()
                    val text=item["content"].arr().joinToString("\n") { it.obj().text("text") }
                    when(item.text("type")) {
                        "UserMessage" -> { prompt=text; answer="" }
                        "AgentMessage" -> if(text.isNotBlank()) { answer=text; answers+=text }
                    }
                }
                "agent_message" -> { answer=p.text("message"); if(answer.isNotBlank()) answers+=answer }
                "task_complete" -> { val final=p.text("last_agent_message").ifBlank { answer }; completed=if(prompt!=null && final.isNotBlank()) prompt to final else null; if(final.isNotBlank()) answers+=final }
            }
            row.text("type")=="response_item" && p.text("role")=="assistant" && p.text("channel")!="analysis" -> {
                val text=p["content"].arr().filter { it.obj().text("type") in setOf("output_text","text") }.joinToString("\n") { it.obj().text("text") }
                if(text.isNotBlank()) { answers+=text; answer=text }
            }
        }
    }
    return CodexScreenTranscript(path,answers.distinct(),prompt,completed)
}
internal fun normalizeCodexDisplay(text:String):String = Normalizer.normalize(text,Normalizer.Form.NFKC)
    .replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"),"")
    .replace(Regex("[\\p{P}\\p{S}]"),"").replace(Regex("\\s+")," ").trim()

internal fun matchCodexScreen(screen:String,candidates:List<CodexScreenTranscript>):String? {
    val shown=screen.substring(maxOf(0,screen.lastIndexOf("OpenAI Codex (v")))
    // Prompt/tool text cannot prove ownership of an assistant answer.
    val blocks=Regex("(?ms)^\\s*• (.*?)(?=^\\s*(?:› |• |─* Worked for |Worked for )|\\z)")
        .findAll(shown).map { normalizeCodexDisplay(it.groupValues[1]) }.toList()
    val matches=mutableSetOf<String>()
    for(candidate in candidates) {
        if(candidate.answers.any { text ->
            val normalized=normalizeCodexDisplay(text)
            normalized.length>=64 && normalized.toSet().size>=12 && blocks.any { it==normalized } &&
                candidates.filter { it.path!=candidate.path }.none { other -> other.answers.any { normalizeCodexDisplay(it).contains(normalized) } }
        }) matches+=candidate.path
    }
    if(matches.size==1) return matches.single()
    if(matches.size>1) return null
    val lines=shown.lines()
    val footer=lines.indexOfLast { Regex("^\\s*(?:[─━]\\s*)*Worked for ").containsMatchIn(it) }
    if(footer<0) return null
    // Only a footer followed by the idle composer can prove a completed screen turn.
    val after=lines.drop(footer+1)
    val composer=after.filter { it.trimStart().startsWith("›") }
    if(composer.size!=1 || composer.single().trim().removePrefix("›").trim() !in setOf("","Ask Codex to do anything")) return null
    val answerAt=(footer-1 downTo 0).firstOrNull { lines[it].trimStart().startsWith("• ") } ?: return null
    val promptAt=(answerAt-1 downTo 0).firstOrNull { lines[it].trimStart().startsWith("› ") } ?: return null
    val prompt=normalizeCodexDisplay(lines.subList(promptAt,answerAt).joinToString("\n").trim().removePrefix("›"))
    val answer=normalizeCodexDisplay(lines.subList(answerAt,footer).joinToString("\n").trim().removePrefix("•"))
    if(prompt.isEmpty() || answer.isEmpty()) return null
    val owners=candidates.filter { c -> c.completed?.let { normalizeCodexDisplay(it.first)==prompt && normalizeCodexDisplay(it.second)==answer }==true }
    val owner=owners.singleOrNull() ?: return null
    if(candidates.any { it.path!=owner.path && (it.latestPrompt==null || normalizeCodexDisplay(it.latestPrompt)==prompt) }) return null
    return owner.path
}
