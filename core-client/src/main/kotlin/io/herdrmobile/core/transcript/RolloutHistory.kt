package io.herdrmobile.core.transcript

import io.herdrmobile.core.HcClient
import io.herdrmobile.core.files.*
import io.herdrmobile.core.rpc.HcError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Revert lineage is a byte-checked prefix of an explicitly named base rollout, never newest. */
internal suspend fun HcClient.rolloutHistory(binding:TranscriptBinding,header:JsonObject):Pair<List<String>,Boolean> {
    val script=checkNotNull(TranscriptSource::class.java.getResourceAsStream("/io/herdrmobile/core/transcript/find_base.py")).bufferedReader().use { it.readText() }
    val seen=mutableSetOf<String>()
    var totalBytes=0L
    suspend fun load(current:String,head:JsonObject,depth:Int):Pair<List<String>,Boolean> {
        val base=head["payload"].obj()["history_base"] as? JsonObject ?: return emptyList<String>() to true
        if(depth>=8) return emptyList<String>() to false
        val rollout=base.text("thread_id")
        val end=(base["end_byte_offset"] as? JsonPrimitive)?.longOrNull ?: return emptyList<String>() to false
        val ordinal=(base["end_ordinal_exclusive"] as? JsonPrimitive)?.longOrNull ?: return emptyList<String>() to false
        if(!seen.add(rollout) || end !in 1..32L*1024*1024) return emptyList<String>() to false
        val found=execRead(listOf("python3","-c",script,current,rollout),on=binding.machine).requireSuccess()
        if(found.truncated) return emptyList<String>() to false
        val path=runCatching { Json.parseToJsonElement(found.stdout.decodeToString()).str() }.getOrDefault("")
        if(path.isEmpty()) return emptyList<String>() to false
        val rows=mutableListOf<String>(); var bytes=0L; var offset=1L
        while(bytes<end) {
            val chunk=readChunk(path,offset=offset,limit=2000,on=binding.machine)
            val complete=chunk.text.split('\n').dropLast(1)
            if(complete.isEmpty()) return emptyList<String>() to false
            for(line in complete) {
                val size=line.toByteArray().size+1
                if(bytes+size>end || totalBytes+size>32L*1024*1024) return emptyList<String>() to false
                totalBytes+=size
                rows+=line; bytes+=size; offset++
                if(bytes==end) break
            }
        }
        val parsed=parseLines(rows)
        if((parsed.lastOrNull()?.get("ordinal") as? JsonPrimitive)?.longOrNull!=ordinal-1) return emptyList<String>() to false
        val first=parsed.firstOrNull() ?: return emptyList<String>() to false
        if(first.text("type")!="session_meta" || first["payload"].obj().text("id")!=binding.sessionId) return emptyList<String>() to false
        val previous=load(path,first,depth+1)
        return (previous.first+rows) to previous.second
    }
    return try { load(binding.path,header,0) }
    catch(e:CancellationException) { throw e } catch(_:HcError) { emptyList<String>() to false }
}
