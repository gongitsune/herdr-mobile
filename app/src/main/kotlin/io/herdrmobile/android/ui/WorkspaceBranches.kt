package io.herdrmobile.android.ui

import androidx.compose.runtime.*
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.android.connection.HostWorkspace
import kotlinx.coroutines.CancellationException

/** Refresh optional metadata only when the drawer opens or the space list changes. */
@Composable
fun rememberWorkspaceBranches(connections: HostConnections, workspaces: List<HostWorkspace>, visible: Boolean): Map<Pair<String, String>, String> {
    val branches = remember { mutableStateMapOf<Pair<String, String>, String>() }
    val spaces = workspaces.flatMap { host -> host.spaces.map { Triple(host.hostId, it.id, it.cwd to it.machine) } }
    LaunchedEffect(connections, visible, spaces) {
        if (!visible) return@LaunchedEffect
        branches.keys.retainAll(spaces.map { it.first to it.second }.toSet())
        spaces.forEach { (host, id, folder) ->
            val branch = try { connections.client(host)?.gitBranch(folder.first, folder.second) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            if (branch == null) branches.remove(host to id) else branches[host to id] = branch
        }
    }
    return branches
}
