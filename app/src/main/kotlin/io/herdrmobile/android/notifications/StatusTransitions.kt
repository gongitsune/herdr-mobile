package io.herdrmobile.android.notifications

import io.herdrmobile.android.connection.ConsoleTerminal
import io.herdrmobile.android.connection.HostStatus
import io.herdrmobile.core.ConnectionState

/** Compare live snapshots only; initial/reconnect snapshots establish a baseline. */
internal class StatusTransitions {
    private var previous = emptyMap<Pair<String, String>, String?>()
    fun update(rows: List<ConsoleTerminal>, hosts: List<HostStatus>, foreground: Boolean): List<ConsoleTerminal> {
        val connected = hosts.filter { it.state is ConnectionState.Connected }.map { it.host.id }.toSet()
        val live = rows.filter { !it.stale && it.hostId in connected }
        val changed = live.filter { row ->
            val key = row.hostId to row.terminal.id
            foreground && previous.containsKey(key) && previous[key] != row.terminal.status?.state &&
                row.terminal.status?.state in setOf("blocked", "done", "error")
        }
        previous = live.associate { (it.hostId to it.terminal.id) to it.terminal.status?.state }
        return changed
    }
}
