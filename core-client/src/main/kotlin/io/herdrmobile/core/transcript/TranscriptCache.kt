package io.herdrmobile.core.transcript

import io.herdrmobile.core.model.Terminal

/** Connection-owned, bounded memory only. No transcript or user selection is written to the host. */
internal class TranscriptCache(private val capacity:Int=16) {
    private data class Key(val terminalId:String,val processKey:String,val path:String)
    private val projections=LinkedHashMap<Key,CachedTranscript>(16,0.75f,true)
    private val retained=LinkedHashMap<Pair<String,String>,Pair<TranscriptBinding,Long>>(16,0.75f,true)

    @Synchronized fun latest(terminalId:String):CachedTranscript? {
        val key=projections.keys.lastOrNull { it.terminalId==terminalId } ?: return null
        return projections[key]
    }
    @Synchronized fun put(terminal:Terminal,value:CachedTranscript) {
        projections[Key(terminal.id,value.binding.processKey,value.binding.path)]=value
        while(projections.size>capacity) projections.remove(projections.keys.first())
    }
    @Synchronized fun invalidate(terminalId:String) {
        invalidateProjections(terminalId)
        retained.keys.removeAll { it.first==terminalId }
    }
    @Synchronized fun invalidateProjections(terminalId:String) {
        projections.keys.removeAll { it.terminalId==terminalId }
    }
    @Synchronized fun retained(terminalId:String,processKey:String):Pair<TranscriptBinding,Long>? = retained[terminalId to processKey]
    @Synchronized fun retain(terminalId:String,processKey:String,value:Pair<TranscriptBinding,Long>?) {
        retained.keys.removeAll { it.first==terminalId && it.second!=processKey }
        if(value==null) retained.remove(terminalId to processKey) else retained[terminalId to processKey]=value
        while(retained.size>capacity) retained.remove(retained.keys.first())
    }
}

// Status, turn and title may change without changing the process that owns a conversation.
internal data class TranscriptProcess(val pid:Long?,val foregroundPid:Int?,val argv:List<String>?,
                                      val createdMs:Long,val identity:String) {
    companion object {
        fun of(terminal:Terminal)=TranscriptProcess(terminal.pid,terminal.foreground?.pid,
            terminal.foreground?.argv,terminal.created_ms,terminal.identity)
    }
}

/** Immutable reader checkpoint; an active source always owns its own mutable line buffer. */
internal data class CachedTranscript(
    val process:TranscriptProcess,
    val binding:TranscriptBinding,
    val ready:TranscriptState.Ready,
    val items:List<ChatItem>,
    val activity:TurnActivity?,
    val session:SessionInfo?,
    val lines:List<String>,
    val nextLine:Long,
    val checkpoint:String?,
    val bytes:Long,
    val lastTail:Pair<Int,Int>?,
    val historyPrefix:List<String>,
    val historyComplete:Boolean,
    val historyLoaded:Boolean,
    val historyBytes:Long,
    val projectedBlocked:Boolean?,
    val projectedActivity:TurnActivity?,
)
