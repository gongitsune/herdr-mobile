package io.herdrmobile.core

import io.herdrmobile.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class TerminalStoreTest {
    @Test fun appliesEveryTerminalEventWithoutLosingOtherFields() {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val store = TerminalStore(HcClient(TransportFactory { error("unused") },scope),scope)
        fun event(type: String, data: String, id: String? = "t1") =
            store.apply(Event(1,type,id,HcJson.parseToJsonElement(data)))
        fun current() = store.terminals.value.single()
        try {
            event("created", """{"address":"t1","cwd":"/tmp","pid":123,"foreground":{"pid":123,"name":"cat"},"state":"live"}""")
            event("status", """{"status":{"state":"blocked","progress":50},"records":{"x":{"state":"working"}},"reported":true}""")
            assertEquals(0,current().priority)
            assertEquals("working",current().records["x"]!!.state)
            event("turn", """{"turn":3,"state":"done"}""")
            assertEquals("live",current().state)
            assertEquals(3L,current().turn)
            event("size", """{"cols":90,"rows":40}""")
            assertEquals(InitialSize(90,40),current().size)
            event("title", """{"title":"hello"}""")
            event("labels", """{"labels":{"test":"1"}}""")
            event("keep", """{"keep":true}""")
            assertTrue(current().keep)
            event("foreground", "null")
            assertNull(current().foreground)
            event("lost", """{"reason":"holder_lost","exit_code":null,"signal":null,"finished_ms":null}""")
            assertNull(current().pid)
            assertEquals("live",current().state)
            event("restarted", """{"address":"t1","pid":456,"cwd":"/tmp","state":"live"}""")
            assertEquals(456L,current().pid)
            event("finished", """{"exit_code":0,"reason":"exited","finished_ms":999}""")
            assertEquals("finished",current().state)
            assertEquals(5,current().priority)
            event("doc", "{}",null)
            event("removed","{}")
            assertTrue(store.terminals.value.isEmpty())
            event("labels", """{"labels":{"test":"1"},"record":{"address":"t2","state":"live"}}""","t2")
            assertEquals("t2",current().id)
        } finally { scope.cancel() }
    }
}
