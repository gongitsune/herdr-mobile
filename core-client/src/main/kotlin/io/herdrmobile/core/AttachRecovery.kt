package io.herdrmobile.core

import io.herdrmobile.core.model.Event
import io.herdrmobile.core.model.Terminal

/** Advice only: the app decides when to replace its AttachSession; input is never replayed. */
enum class AttachRecovery { NONE, WAIT_FOR_RESTART, REATTACH, FINISHED }

/** Ignores other terminals; lost streams end even if the kept terminal will restart. */
fun Event.attachRecovery(terminalId: String): AttachRecovery =
    if (terminal != terminalId) AttachRecovery.NONE else when (type) {
        "lost" -> AttachRecovery.WAIT_FOR_RESTART
        "restarted" -> AttachRecovery.REATTACH
        "finished", "removed" -> AttachRecovery.FINISHED
        else -> AttachRecovery.NONE
    }

/** Compare consecutive store records, including snapshots obtained after reconnect. */
fun Terminal.attachRecovery(previous: Terminal?): AttachRecovery = when {
    state == "finished" -> AttachRecovery.FINISHED
    state == "live" && pid == null && reason != null -> AttachRecovery.WAIT_FOR_RESTART
    previous != null && previous.id == id && state == "live" && pid != null &&
        (previous.pid != pid || previous.reason != null) -> AttachRecovery.REATTACH
    else -> AttachRecovery.NONE
}
