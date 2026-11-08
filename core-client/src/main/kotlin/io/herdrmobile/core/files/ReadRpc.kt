package io.herdrmobile.core.files

import io.herdrmobile.core.HcClient
import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.rpc.HcError
import kotlinx.serialization.json.*
import java.util.Base64

internal data class ExecRead(val code:Int?,val stdout:ByteArray,val stderr:ByteArray,val truncated:Boolean,val timedOut:Boolean)
internal suspend fun HcClient.execRead(argv:List<String>,cwd:String?=null,on:String?=null):ExecRead {
    val result=call("machine.exec",buildJsonObject {
        put("argv",JsonArray(argv.map(::JsonPrimitive))); put("timeout",10)
        cwd?.let { put("cwd",it) }; on?.let { put("on",it) }
    }).jsonObject
    return ExecRead((result["code"] as? JsonPrimitive)?.intOrNull,
        Base64.getDecoder().decode(result["stdout"]!!.jsonPrimitive.content),
        Base64.getDecoder().decode(result["stderr"]!!.jsonPrimitive.content),
        result["truncated"]!!.jsonPrimitive.boolean,result["timed_out"]!!.jsonPrimitive.boolean)
}
internal fun ExecRead.requireSuccess():ExecRead {
    if(code!=0 || timedOut) throw HcError("io","Read-only command failed (exit=$code, timeout=$timedOut)")
    return this
}
internal suspend fun HcClient.readChunk(path:String,cwd:String?=null,offset:Long=1,limit:Int=2000,on:String?=null):FileChunk {
    require(offset>=1 && limit>0)
    return HcJson.decodeFromJsonElement(call("file.read",buildJsonObject {
        put("path",path); put("offset",offset); put("limit",limit)
        cwd?.let { put("cwd",it) }; on?.let { put("on",it) }
    }))
}
