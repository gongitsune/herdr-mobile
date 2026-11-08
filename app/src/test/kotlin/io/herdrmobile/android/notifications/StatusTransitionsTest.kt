package io.herdrmobile.android.notifications

import io.herdrmobile.android.SavedHost
import io.herdrmobile.android.connection.ConsoleTerminal
import io.herdrmobile.android.connection.HostStatus
import io.herdrmobile.core.ConnectionState
import io.herdrmobile.core.model.CoreInfo
import io.herdrmobile.core.model.Status
import io.herdrmobile.core.model.Terminal
import org.junit.Assert.*
import org.junit.Test

class StatusTransitionsTest {
    private val connected = listOf(HostStatus(SavedHost(id = "host", name = "Host", host = "host", user = "user"), ConnectionState.Connected(CoreInfo(), emptySet())))
    private fun row(status: String?, id: String = "one", stale: Boolean = false) = ConsoleTerminal("host", "Host", Terminal(address = id, status = status?.let { Status(state = it) }), stale)

    @Test fun initialSnapshotDoesNotNotifyButLaterChangesDo() {
        val tracker = StatusTransitions()
        assertTrue(tracker.update(listOf(row("blocked")), connected, true).isEmpty())
        assertTrue(tracker.update(listOf(row("working")), connected, true).isEmpty())
        assertEquals("blocked", tracker.update(listOf(row("blocked")), connected, true).single().terminal.status?.state)
        assertTrue(tracker.update(listOf(row("blocked")), connected, true).isEmpty())
        assertEquals("done", tracker.update(listOf(row("done")), connected, true).single().terminal.status?.state)
        assertEquals("error", tracker.update(listOf(row("error")), connected, true).single().terminal.status?.state)
    }
    @Test fun backgroundChangesAreNotReplayedWhenReturningToForeground() {
        val tracker = StatusTransitions()
        tracker.update(listOf(row("working")), connected, true)
        assertTrue(tracker.update(listOf(row("error")), connected, false).isEmpty())
        assertTrue(tracker.update(listOf(row("error")), connected, true).isEmpty())
    }
    @Test fun reconnectSnapshotIsBaselineAndStaleRecordsNeverNotify() {
        val tracker = StatusTransitions()
        tracker.update(listOf(row("working")), connected, true)
        val reconnecting = connected.map { it.copy(state = ConnectionState.Reconnecting(1, 1000)) }
        assertTrue(tracker.update(listOf(row("error", stale = true)), reconnecting, true).isEmpty())
        assertTrue(tracker.update(listOf(row("error")), connected, true).isEmpty())
        assertEquals(1, tracker.update(listOf(row("blocked")), connected, true).size)
    }
    @Test fun nullStatusCanTransitionAndNewOrRemovedTerminalsDoNotLeakState() {
        val tracker = StatusTransitions()
        tracker.update(listOf(row(null)), connected, true)
        assertEquals(1, tracker.update(listOf(row("blocked"), row("error", "new")), connected, true).size)
        tracker.update(emptyList(), connected, true)
        assertTrue(tracker.update(listOf(row("error")), connected, true).isEmpty())
    }
}
