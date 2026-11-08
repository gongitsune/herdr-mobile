package io.herdrmobile.android

import io.herdrmobile.core.model.Status
import io.herdrmobile.core.model.Terminal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ConsoleTerminal(val hostId: String, val hostName: String, val terminal: Terminal)
class FakeTerminalStore {
    val terminals: StateFlow<List<ConsoleTerminal>> = MutableStateFlow(
        listOf("working", "blocked", "error", "done", "idle").mapIndexed { index, state ->
            ConsoleTerminal("demo", "Demo host", Terminal(address = "demo-$index", name = "Agent ${index + 1}",
                title = listOf("Build Android client", "Approval required", "Tests need attention", "Review complete", "Ready")[index],
                cwd = "~/develop/herdr-mobile", state = "running", status = Status(state = state)))
        }
    )
}
