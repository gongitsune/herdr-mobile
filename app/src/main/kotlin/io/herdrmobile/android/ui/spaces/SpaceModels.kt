package io.herdrmobile.android.ui.spaces

/** Presentation models isolate the UI from document/log transport details. */
data class SpacePane(val id: String, val title: String, val terminalId: String, val status: String?, val cwd: String)
data class SpaceTab(val id: String, val name: String, val panes: List<SpacePane>)
data class MobileSpace(val id: String, val hostId: String, val hostName: String, val name: String, val folder: String, val tabs: List<SpaceTab>, val demo: Boolean = false, val machine: String? = null)

object FakeSpaces {
    val spaces = listOf(MobileSpace("mobile", "demo", "Demo host", "herdr-mobile", "~/develop/herdr-mobile", listOf(
        SpaceTab("agents", "Agents", listOf(SpacePane("agent", "Android client", "demo-agent", "blocked", "~/develop/herdr-mobile"), SpacePane("review", "Review", "demo-review", "working", "~/develop/herdr-mobile"))),
        SpaceTab("shell", "Shell", listOf(SpacePane("shell", "Shell", "demo-shell", "idle", "~/develop/herdr-mobile"))),
    ), demo = true))
}

fun io.herdrmobile.android.connection.HostWorkspace.mobileSpaces(): List<MobileSpace> = spaces.map { space ->
    MobileSpace(space.id, hostId, hostName, space.name, space.cwd, space.tabs.map { tab ->
        SpaceTab(tab.id, tab.name, tab.panes.map { pane ->
            SpacePane(pane.id, pane.terminal.title.ifBlank { pane.terminal.name ?: pane.agent?.program ?: pane.terminal.id },
                pane.terminal.id, pane.terminal.status?.state, pane.terminal.foreground?.cwd?.takeIf { it.isNotBlank() } ?: pane.terminal.cwd.ifBlank { space.cwd })
        })
    }, machine = space.machine)
}
