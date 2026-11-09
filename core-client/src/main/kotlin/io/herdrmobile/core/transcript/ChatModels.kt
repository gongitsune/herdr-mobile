package io.herdrmobile.core.transcript

import io.herdrmobile.core.workspace.AgentKind
import io.herdrmobile.core.rpc.HcError

enum class ToolState { RUNNING, SUCCEEDED, FAILED }
data class ChatOption(val label: String, val description: String? = null, val keys: List<String> = emptyList())
sealed interface ChatItem {
    val id: String
    val atEpochMs: Long?
    data class UserMessage(override val id:String,val text:String,override val atEpochMs:Long?=null):ChatItem
    data class AssistantText(override val id:String,val markdown:String,override val atEpochMs:Long?=null):ChatItem
    data class Thinking(override val id:String,val text:String,override val atEpochMs:Long?=null):ChatItem
    data class ToolCall(override val id:String,val name:String,val inputSummary:String,val result:String?=null,val state:ToolState=ToolState.RUNNING,val verb:String?=null,val target:String?=null,override val atEpochMs:Long?=null):ChatItem
    data class Question(override val id:String,val prompt:String,val options:List<ChatOption>,val approval:Boolean=false,override val atEpochMs:Long?=null):ChatItem
    data class TurnMarker(override val id:String,val label:String,override val atEpochMs:Long?=null):ChatItem
}
data class TurnActivity(val running:Boolean,val startedAtEpochMs:Long?,val lastStep:String?)
data class TranscriptBinding(val kind:AgentKind,val sessionId:String,val path:String,val processKey:String,
                             val machine:String?=null,val confirmed:Boolean=false)
data class TranscriptCandidate(val kind:AgentKind,val sessionId:String,val path:String,val processKey:String,val machine:String?=null)
sealed interface TranscriptState {
    data object Loading:TranscriptState
    /** The agent is verified, but its conversation has not started yet. */
    data class NotStarted(val kind:AgentKind):TranscriptState
    data class Ready(val binding:TranscriptBinding,val complete:Boolean):TranscriptState
    data class Unavailable(val reason:String,val candidates:List<TranscriptCandidate> = emptyList()):TranscriptState
    data class Failed(val error:HcError):TranscriptState
}
