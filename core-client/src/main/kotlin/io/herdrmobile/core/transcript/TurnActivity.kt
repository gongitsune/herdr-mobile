package io.herdrmobile.core.transcript

import io.herdrmobile.core.model.Terminal

/** Activity follows visible branch/revert projection, so abandoned turns cannot reset its clock. */
internal fun turnActivity(items:List<ChatItem>):TurnActivity? {
    var activity:TurnActivity?=null
    var awaitingUser=false
    for(item in items) {
        when(item) {
            is ChatItem.UserMessage -> {
                activity=if(awaitingUser) TurnActivity(true,activity?.startedAtEpochMs ?: item.atEpochMs,null)
                    else TurnActivity(true,item.atEpochMs,null)
                awaitingUser=false
            }
            is ChatItem.TurnMarker -> when(item.label) {
                "Turn started" -> { activity=TurnActivity(true,item.atEpochMs,null); awaitingUser=true }
                "Turn complete","Turn interrupted" -> { activity=activity?.copy(running=false); awaitingUser=false }
            }
            is ChatItem.Thinking -> activity=(activity ?: TurnActivity(true,null,null)).copy(lastStep="Thinking")
            is ChatItem.ToolCall -> activity=(activity ?: TurnActivity(true,null,null)).copy(lastStep=item.name.take(80))
            is ChatItem.AssistantText -> activity=(activity ?: TurnActivity(true,null,null)).copy(lastStep="Writing")
            is ChatItem.Question -> Unit
        }
    }
    return activity
}

internal fun TurnActivity?.withTerminal(terminal:Terminal):TurnActivity? {
    val status=terminal.status?.state
    if(this==null && status !in setOf("working","blocked")) return null
    val running=terminal.state!="finished" && when(status) {
        "working" -> true
        "blocked","idle","done","error" -> false
        else -> this?.running==true
    }
    return this?.copy(running=running) ?: TurnActivity(running,null,null)
}
