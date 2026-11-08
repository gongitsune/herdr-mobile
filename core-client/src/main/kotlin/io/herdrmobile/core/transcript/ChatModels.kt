package io.herdrmobile.core.transcript

import io.herdrmobile.core.workspace.AgentKind
import io.herdrmobile.core.rpc.HcError

enum class ToolState { RUNNING, SUCCEEDED, FAILED }
data class ChatOption(val label: String, val description: String? = null, val keys: List<String> = emptyList())
sealed interface ChatItem {
    val id: String
    data class UserMessage(override val id:String,val text:String):ChatItem
    data class AssistantText(override val id:String,val markdown:String):ChatItem
    data class Thinking(override val id:String,val text:String):ChatItem
    data class ToolCall(override val id:String,val name:String,val inputSummary:String,val result:String?=null,val state:ToolState=ToolState.RUNNING):ChatItem
    data class Question(override val id:String,val prompt:String,val options:List<ChatOption>,val approval:Boolean=false):ChatItem
    data class TurnMarker(override val id:String,val label:String):ChatItem
}
data class TranscriptBinding(val kind:AgentKind,val sessionId:String,val path:String,val processKey:String,
                             val machine:String?=null,val confirmed:Boolean=false)
data class TranscriptCandidate(val kind:AgentKind,val sessionId:String,val path:String,val processKey:String,val machine:String?=null)
sealed interface TranscriptState {
    data object Loading:TranscriptState
    data class Ready(val binding:TranscriptBinding,val complete:Boolean):TranscriptState
    data class Unavailable(val reason:String,val candidates:List<TranscriptCandidate> = emptyList()):TranscriptState
    data class Failed(val error:HcError):TranscriptState
}
