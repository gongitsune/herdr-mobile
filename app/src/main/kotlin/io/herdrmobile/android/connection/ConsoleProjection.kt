package io.herdrmobile.android.connection

import io.herdrmobile.core.ConnectionState
import io.herdrmobile.core.model.Terminal

/** Keep stale records visibly distinct and never treat them as current connection data. */
internal fun consoleRows(hostId: String, hostName: String, state: ConnectionState, live: List<Terminal>, stale: List<Terminal>): List<ConsoleTerminal> {
    val current = if (state is ConnectionState.Connected) live else emptyList()
    val waiting = state is ConnectionState.Reconnecting || state is ConnectionState.Connecting || state is ConnectionState.Connected
    return if (current.isNotEmpty()) current.map { ConsoleTerminal(hostId, hostName, it) }
    else if (waiting) stale.map { ConsoleTerminal(hostId, hostName, it, stale = true) }
    else emptyList()
}
