package io.herdrmobile.core

import io.herdrmobile.core.model.*
import io.herdrmobile.core.rpc.CoreConnection
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class AttachRecoveryTest {
    @Test fun eventsAdviseWaitingAndReattachOnlyForMatchingTerminal() {
        fun event(type: String)=Event(1,type,"t1")
        assertEquals(AttachRecovery.WAIT_FOR_RESTART,event("lost").attachRecovery("t1"))
        assertEquals(AttachRecovery.REATTACH,event("restarted").attachRecovery("t1"))
        assertEquals(AttachRecovery.FINISHED,event("removed").attachRecovery("t1"))
        assertEquals(AttachRecovery.FINISHED,event("finished").attachRecovery("t1"))
        assertEquals(AttachRecovery.NONE,event("status").attachRecovery("t1"))
        assertEquals(AttachRecovery.NONE,event("restarted").attachRecovery("other"))
    }
    @Test fun storeRecordsDistinguishLostRestartedAndFinished() {
        val live=Terminal(address="t1",state="live",pid=11)
        val lost=live.copy(pid=null,reason="holder_lost")
        val restarted=live.copy(pid=12)
        assertEquals(AttachRecovery.NONE,live.attachRecovery(null))
        assertEquals(AttachRecovery.NONE,live.attachRecovery(live))
        assertEquals(AttachRecovery.WAIT_FOR_RESTART,lost.attachRecovery(live))
        assertEquals(AttachRecovery.REATTACH,restarted.attachRecovery(lost))
        assertEquals(AttachRecovery.REATTACH,restarted.attachRecovery(live))
        assertEquals(AttachRecovery.NONE,restarted.attachRecovery(live.copy(address="other")))
        assertEquals(AttachRecovery.FINISHED,live.copy(state="finished").attachRecovery(live))
    }
    @Test fun channelRequestedAfterDisconnectIsAlreadyClosed(): Unit = runBlocking {
        withTimeout(3000) {
            val peer=FakeCoreTransport()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val connection=CoreConnection(peer,scope)
            try {
                connection.start()
                peer.close()
                runCatching { connection.completion.await() }
                assertTrue(connection.channel(99).receiveCatching().isClosed)
            } finally { connection.close(); scope.cancel() }
        }
    }
}
