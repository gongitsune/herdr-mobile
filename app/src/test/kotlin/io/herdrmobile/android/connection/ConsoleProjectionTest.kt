package io.herdrmobile.android.connection

import io.herdrmobile.core.ConnectionState
import io.herdrmobile.core.model.CoreInfo
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.rpc.HcError
import org.junit.Assert.*
import org.junit.Test

class ConsoleProjectionTest {
    private val old = listOf(Terminal(address = "old"))
    private val fresh = listOf(Terminal(address = "fresh"))
    @Test fun reconnectingUsesOnlyStaleRecords() {
        val rows = consoleRows("host", "Host", ConnectionState.Reconnecting(1, 1000), fresh, old)
        assertEquals("old", rows.single().terminal.id)
        assertTrue(rows.single().stale)
    }
    @Test fun connectedSnapshotReplacesStaleRows() {
        val rows = consoleRows("host", "Host", ConnectionState.Connected(CoreInfo(), emptySet()), fresh, old)
        assertEquals("fresh", rows.single().terminal.id)
        assertFalse(rows.single().stale)
    }
    @Test fun connectedWhileAwaitingSnapshotRetainsDimmedRows() {
        val rows = consoleRows("host", "Host", ConnectionState.Connected(CoreInfo(), emptySet()), emptyList(), old)
        assertTrue(rows.single().stale)
    }
    @Test fun explicitDisconnectAndPermanentFailureHideStaleRows() {
        assertTrue(consoleRows("host", "Host", ConnectionState.Disconnected, fresh, old).isEmpty())
        assertTrue(consoleRows("host", "Host", ConnectionState.Failed(HcError("auth", "Failed")), fresh, old).isEmpty())
    }
}
