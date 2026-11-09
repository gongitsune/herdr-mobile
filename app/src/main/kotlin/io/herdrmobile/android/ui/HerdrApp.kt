package io.herdrmobile.android.ui

import androidx.activity.compose.BackHandler
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import io.herdrmobile.android.ui.files.*
import io.herdrmobile.core.workspace.Space
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.herdrmobile.android.MainActivity

import androidx.core.content.edit
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.herdrmobile.android.HerdrApplication
import io.herdrmobile.android.R
import io.herdrmobile.android.connection.ConsoleTerminal
import io.herdrmobile.android.notifications.TerminalDestination
import io.herdrmobile.android.ui.hosts.*
import io.herdrmobile.android.ui.terminal.*
import io.herdrmobile.android.ui.theme.ProgramAvatar
import io.herdrmobile.android.ui.theme.statusColor
import io.herdrmobile.core.ConnectionState
import io.herdrmobile.core.workspace.AgentDetector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HerdrApp(app: HerdrApplication, pendingDestination: StateFlow<TerminalDestination?>, consumed: () -> Unit) {
    val preferences = LocalContext.current.getSharedPreferences("agent-screen", 0)
    var host by rememberSaveable { mutableStateOf(preferences.getString("host", null)) }
    var terminal by rememberSaveable { mutableStateOf(preferences.getString("terminal", null)) }
    var selectedSpaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var emptiedSpace by remember { mutableStateOf<Pair<String, Space>?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val drawer = rememberDrawerState(if (terminal == null) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val workspaces by app.connections.workspaces.collectAsStateWithLifecycle()
    val statuses by app.connections.statuses.collectAsStateWithLifecycle()
    val agents by app.connections.agents.collectAsStateWithLifecycle()
    val readMarks = remember { mutableStateMapOf<String, String>() }
    fun markKey(hostId: String, id: String) = "read:$hostId:$id"
    fun revision(row: ConsoleTerminal) = "${row.terminal.turn}:${row.terminal.status_ms}"
    fun unread(row: ConsoleTerminal): Boolean {
        val key = markKey(row.hostId, row.terminal.id)
        return row.terminal.status?.state == "done" && (readMarks[key] ?: preferences.getString(key, null)) != revision(row)
    }
    LaunchedEffect(agents, host, terminal, settings) {
        agents.find { it.hostId == host && it.terminal.id == terminal && !it.stale && !settings }?.let { row ->
            val key = markKey(row.hostId, row.terminal.id)
            readMarks[key] = revision(row)
            preferences.edit { putString(key, revision(row)) }
        }
    }
    LaunchedEffect(app) {
        app.hosts.hosts.collect { hosts ->
            if (host != null && hosts.none { it.id == host }) {
                host = null; terminal = null; selectedSpaceId = null
                preferences.edit { remove("host"); remove("terminal") }
                drawer.open()
            }
        }
    }
    var files by remember { mutableStateOf<Pair<String, Space>?>(null) }

    var spaceActions by remember { mutableStateOf<Pair<String, Space>?>(null) }
    var tabSheet by remember { mutableStateOf<Pair<String, Space>?>(null) }
    var closing by remember { mutableStateOf<Triple<String, Space, io.herdrmobile.core.workspace.Tab>?>(null) }
    var closeBusy by remember { mutableStateOf(false) }
    var closeErrors by remember { mutableStateOf<List<Pair<String, io.herdrmobile.core.rpc.HcError>>>(emptyList()) }
    var renaming by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var renameOriginal by remember { mutableStateOf("") }
    var renamePlaceholder by remember { mutableStateOf("") }
    var renameBusy by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<io.herdrmobile.core.rpc.HcError?>(null) }
    var shellOnly by remember { mutableStateOf(false) }
    var newSpace by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf<Pair<String, Space>?>(null) }
    val terminalRows by app.connections.terminals.collectAsStateWithLifecycle()
    val connection = statuses.find { it.host.id == host }?.state
    LaunchedEffect(host, terminal, connection, terminalRows) {
        val selectedHost = host ?: return@LaunchedEffect
        val selectedTerminal = terminal ?: return@LaunchedEffect
        if (connection is ConnectionState.Connected && terminalRows.none { it.hostId == selectedHost && it.terminal.id == selectedTerminal && !it.stale }) {
            // An explicit list response distinguishes an empty snapshot from a pending one.
            val rows = try { app.connections.client(selectedHost)?.list(all = true) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (rows != null && rows.none { it.id == selectedTerminal } && host == selectedHost && terminal == selectedTerminal) {
                host = null; terminal = null; selectedSpaceId = null
                preferences.edit { remove("host"); remove("terminal") }
                drawer.open()
            }
        }
    }
    val trust by app.connections.trust.collectAsStateWithLifecycle()
    val pending by pendingDestination.collectAsStateWithLifecycle()
    val hostsVm: HostsViewModel = viewModel(factory = factory { HostsViewModel(app.hosts, app.connections) })
    fun open(hostId: String, terminalId: String) {
        emptiedSpace = null
        host = hostId; terminal = terminalId; settings = false
        val space = workspaces.filter { it.hostId == hostId }.flatMap { it.spaces }.find { it.tabs.any { tab -> tab.panes.any { it.terminal.id == terminalId || it.subAgents.any { child -> child.terminal.id == terminalId } } } }
        selectedSpaceId = space?.id
        space?.tabs?.find { it.panes.any { it.terminal.id == terminalId || it.subAgents.any { child -> child.terminal.id == terminalId } } }?.let { tab ->
            preferences.edit { putString("tab:$hostId:${space.id}", tab.id) }
        }
        preferences.edit { putString("host", hostId); putString("terminal", terminalId) }
        scope.launch { drawer.close() }
    }
    fun openSpace(hostId: String, space: Space) {
        emptiedSpace = null
        val pane = space.openingPane(preferences.getString("tab:$hostId:${space.id}", null))
        if (pane != null) {
            open(hostId, pane.terminal.id)
        } else {
            host = hostId; terminal = null; selectedSpaceId = space.id; settings = false
            preferences.edit { putString("host", hostId); remove("terminal") }
            scope.launch { drawer.close() }
        }
    }
    fun create(hostId: String, space: Space, shell: Boolean = false, fresh: Boolean = false) {
        shellOnly = shell; newSpace = fresh; creating = hostId to space
        spaceActions = null; tabSheet = null
    }
    fun canRename(hostId: String, spaceId: String) = workspaces.any { it.hostId == hostId && spaceId in it.renameableSpaceIds }
    fun rename(hostId: String, space: Space, tab: io.herdrmobile.core.workspace.Tab? = null) {
        renaming = Triple(hostId, space.id, tab?.id)
        renameOriginal = tab?.explicitName ?: space.explicitName
        renameValue = renameOriginal
        renamePlaceholder = tab?.displayTitle() ?: space.name
        renameError = null
    }
    val selectedSpace = workspaces.filter { it.hostId == host }.flatMap { it.spaces }
        .find { it.id == selectedSpaceId || it.tabs.any { tab -> tab.panes.any { pane -> pane.terminal.id == terminal || pane.subAgents.any { it.terminal.id == terminal } } } }
        ?: emptiedSpace?.takeIf { it.first == host && it.second.id == selectedSpaceId && terminal == null }?.second
    LaunchedEffect(pending) { pending?.let { open(it.hostId, it.terminalId); consumed() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        BackHandler(enabled = settings || (!wide && drawer.isOpen)) { if (settings) settings = false else scope.launch { drawer.close() } }
        val branches = rememberWorkspaceBranches(app.connections, workspaces, wide || drawer.isOpen)
        val drawerContent: @Composable () -> Unit = {
            Column(Modifier.width(300.dp).fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    if (statuses.isEmpty()) TextButton(onClick = { settings = true; hostsVm.edit(null); scope.launch { drawer.close() } }) {
                        Icon(Icons.Default.Add, null); Text(stringResource(R.string.add_host))
                    }
                    statuses.forEach { status ->
                        var expanded by rememberSaveable(status.host.id) { mutableStateOf(true) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Computer, null, Modifier.size(20.dp))
                            Text(status.host.name, Modifier.weight(1f).padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.size(7.dp).background(if (status.state is ConnectionState.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, CircleShape))
                            IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, stringResource(R.string.collapse)) }
                        }
                        if (expanded) {
                        workspaces.filter { it.hostId == status.host.id }.flatMap { it.spaces }.forEach { space ->
                            Row(Modifier.padding(vertical = 4.dp).background(if (host == status.host.id && selectedSpace?.id == space.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.small), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.FolderOpen, null, Modifier.size(20.dp))
                                Column(Modifier.weight(1f)
                                    .clickable { openSpace(status.host.id, space) }.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) {
                                    Text(space.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    branches[status.host.id to space.id]?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                AgentMark(space.tabs.mapNotNull { it.aggregateState() }.minByOrNull { when(it) { "blocked", "error" -> 0; "working" -> 1; "done" -> 2; else -> 3 } }, space.tabs.flatMap { it.panes }.any { pane -> agents.any { it.hostId == status.host.id && it.terminal.id == pane.terminal.id && unread(it) } })
                                IconButton(onClick = { spaceActions = status.host.id to space }) { Icon(Icons.Default.MoreHoriz, stringResource(R.string.space_actions, space.name)) }
                            }
                        }
                        TextButton(onClick = { create(status.host.id, Space(freshSpaceId(), "", "", emptyList()), fresh = true) }, enabled = status.state is ConnectionState.Connected) {
                            Icon(Icons.Default.Add, null); Text(stringResource(R.string.new_space))
                        }
                        }
                    }
                }
                var allAgents by rememberSaveable { mutableStateOf(preferences.getBoolean("agents-expanded", false)) }
                var resting by rememberSaveable { mutableStateOf(false) }
                val (activeAgents, restingAgents) = agents.sortedBy { it.terminal.priority }.partition { it.needsAttention(unread(it)) }
                fun agentSpace(row: ConsoleTerminal) = workspaces.filter { it.hostId == row.hostId }.flatMap { it.spaces }.find { it.tabs.any { tab -> tab.panes.any { it.terminal.id == row.terminal.id || it.subAgents.any { child -> child.terminal.id == row.terminal.id } } } }?.name.orEmpty()
                TextButton(onClick = { allAgents = !allAgents; preferences.edit { putBoolean("agents-expanded", allAgents) } }, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (allAgents) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null)
                    Text(stringResource(R.string.agents), Modifier.weight(1f))
                    val waiting = agents.count { !it.stale && it.terminal.status?.state in listOf("blocked", "error") }
                    if (waiting > 0) Text(stringResource(R.string.agents_waiting, waiting), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                if (allAgents) Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    activeAgents.forEach { row -> DrawerAgent(row, host == row.hostId && terminal == row.terminal.id, agentSpace(row), unread(row)) { open(row.hostId, row.terminal.id) } }
                    if (restingAgents.isNotEmpty()) TextButton(onClick = { resting = !resting }) {
                        Icon(if (resting) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null)
                        Text(stringResource(R.string.agents_resting, restingAgents.size))
                    }
                    if (resting) restingAgents.forEach { row -> DrawerAgent(row, host == row.hostId && terminal == row.terminal.id, agentSpace(row), unread(row)) { open(row.hostId, row.terminal.id) } }
                }
                IconButton(onClick = { settings = true; scope.launch { drawer.close() } }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
            }
        }
        // The store follows the selected terminal, including while settings covers it.
        val selectedVm: TerminalViewModel? = if (host != null && terminal != null) key(host, terminal) {
            val store = remember { ViewModelStore() }
            val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = store } }
            DisposableEffect(store) { onDispose { store.clear() } }
            val vm: TerminalViewModel = viewModel(viewModelStoreOwner = owner, factory = factory { TerminalViewModel(app.connections, host!!, terminal!!) })
            vm
        } else null
        val content: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize()) {
            if (host != null && terminal != null) key(host, terminal) {
                val space = selectedSpace
                val tab = space?.tabs?.find { it.panes.any { pane -> pane.terminal.id == terminal || pane.subAgents.any { it.terminal.id == terminal } } }
                TerminalScreen(checkNotNull(selectedVm), showMenu = !wide,
                    titleContent = { WorkspaceTitle(tab?.displayTitle()?.takeIf { it.isNotBlank() } ?: terminalRows.find { it.hostId == host && it.terminal.id == terminal }?.terminal?.let { it.title.ifBlank { it.name ?: it.id } }.orEmpty(), space?.name.orEmpty(), tab?.openingPane()?.agent?.kind, space != null) { space?.let { tabSheet = host!! to it } } },
                    relatedAgents = {
                        val parent = tab?.panes?.find { it.terminal.id == terminal || it.subAgents.any { child -> child.terminal.id == terminal } }
                        if (!parent?.subAgents.isNullOrEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            val links = if (parent!!.terminal.id == terminal) parent.subAgents else listOf(parent) + parent.subAgents
                            links.forEach { child -> TextButton(onClick = { open(host!!, child.terminal.id) }) { Text(child.terminal.title.ifBlank { child.terminal.name ?: child.id }, maxLines = 1) } }
                        }
                    },
                    workspaceFiles = space?.let { { files = host!! to it } },
                    renameTab = tab?.let { { rename(host!!, space, it) } },
                    canRename = space?.let { canRename(host!!, it.id) } == true,
                    closeTab = tab?.let { { closing = Triple(host!!, space, it); closeErrors = emptyList() } },
                ) { scope.launch { if (drawer.isOpen) drawer.close() else drawer.open() } }
            } else Scaffold(topBar = {
                TopAppBar(title = { WorkspaceTitle(selectedSpace?.name.orEmpty(), "", null, selectedSpace != null) { selectedSpace?.let { tabSheet = host!! to it } } }, navigationIcon = { if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, stringResource(R.string.open_menu)) } })
            }) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(if (selectedSpace != null) R.string.empty_space_create_terminal else R.string.select_agent))
                    selectedSpace?.let { space -> TextButton(onClick = { create(host!!, space, shell = true) }) {
                        Icon(Icons.Default.Add, null); Text(stringResource(R.string.new_terminal))
                    } }
                }
            } }
            if (settings) Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, actions = { NotificationPermissionAction() }, navigationIcon = { IconButton(onClick = { settings = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) }) { padding -> Box(Modifier.padding(padding)) { HostsScreen(hostsVm) } }
            }
        }
        if (wide) Row { Surface { drawerContent() }; Box(Modifier.weight(1f)) { content() } }
        else ModalNavigationDrawer(drawerState = drawer, drawerContent = { ModalDrawerSheet { drawerContent() } }) { content() }
    }
    files?.let { (hostId, space) -> key(hostId, space.id) {
        val store = remember { ViewModelStore() }
        val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = store } }
        DisposableEffect(store) { onDispose { store.clear() } }
        val vm: WorkspaceFilesViewModel = viewModel(viewModelStoreOwner = owner, factory = factory { WorkspaceFilesViewModel(app.connections, hostId, space.cwd, space.machine) })
        WorkspaceSheet(vm, WorkspacePanel.CHANGES) { files = null }
    } }
    creating?.let { (hostId, space) -> NewTerminalSheet(app.connections, hostId, space, { creating = null }, { id -> creating = null; open(hostId, id) }, shellOnly, newSpace) }
    tabSheet?.let { (hostId, snapshot) ->
        val space = workspaces.filter { it.hostId == hostId }.flatMap { it.spaces }.find { it.id == snapshot.id }
        if (space == null) LaunchedEffect(hostId, snapshot.id) { tabSheet = null }
        else WorkspaceTabsSheet(space, terminal.takeIf { host == hostId }, { tabSheet = null }, { tab ->
            if (!(host == hostId && tab.containsTerminal(terminal))) {
                val pane = tab.openingPane()
                if (pane != null) open(hostId, pane.terminal.id)
                else {
                    emptiedSpace = null; host = hostId; terminal = null; selectedSpaceId = space.id; settings = false
                    preferences.edit { putString("host", hostId); remove("terminal"); putString("tab:$hostId:${space.id}", tab.id) }
                }
            }
            tabSheet = null
        }, { tab -> closing = Triple(hostId, space, tab); closeErrors = emptyList() },
            { create(hostId, space, shell = true) }, { create(hostId, space) },
            canRename(hostId, space.id), { rename(hostId, space, it) })
    }
    spaceActions?.let { (hostId, snapshot) ->
        val space = workspaces.filter { it.hostId == hostId }.flatMap { it.spaces }.find { it.id == snapshot.id }
        if (space == null) LaunchedEffect(hostId, snapshot.id) { spaceActions = null }
        else {
            val branches = rememberWorkspaceBranches(app.connections, workspaces, true)
            SpaceActionsSheet(space, branchesForSpace = branches[hostId to space.id], dismiss = { spaceActions = null },
                files = { spaceActions = null; files = hostId to space }, shell = { create(hostId, space, shell = true) },
                agent = { create(hostId, space) }, canRename = canRename(hostId, space.id),
                rename = { rename(hostId, space) })
        }
    }
    closing?.let { (hostId, space, tab) ->
        AlertDialog(onDismissRequest = { if (!closeBusy) closing = null }, title = { Text(stringResource(R.string.close_tab)) },
            text = { Column { Text(stringResource(R.string.close_tab_confirm, tab.displayTitle())); closeErrors.forEach { (id, error) -> Text("$id: ${terminalErrorMessage(error)}", color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(enabled = !closeBusy, onClick = {
                closeBusy = true
                scope.launch {
                    try {
                        val latest = app.connections.workspaces.value.filter { it.hostId == hostId }.flatMap { it.spaces }.find { it.id == space.id }
                        val latestTab = latest?.tabs?.find { it.id == tab.id }
                        val wasOpen = host == hostId && (latestTab ?: tab).containsTerminal(terminal)
                        if (wasOpen) {
                            val next = latest?.tabs?.firstOrNull { it.id != tab.id && it.openingPane() != null }
                            if (next != null) open(hostId, next.openingPane()!!.terminal.id)
                            else { emptiedSpace = hostId to space.copy(tabs = emptyList()); host = hostId; terminal = null; selectedSpaceId = space.id; preferences.edit { putString("host", hostId); remove("terminal"); remove("tab:$hostId:${space.id}") } }
                            tabSheet = null
                        }
                        val client = checkNotNull(app.connections.client(hostId))
                        closeErrors = closeTabTerminals(latestTab?.terminalIds().orEmpty(), client::kill)
                        if (closeErrors.isEmpty()) closing = null
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { closeErrors = listOf(tab.displayTitle() to e.asHcError()) }
                    finally { closeBusy = false }
                }
            }) { Text(stringResource(R.string.close_tab)) } },
            dismissButton = { TextButton(enabled = !closeBusy, onClick = { closing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    renaming?.let { (hostId, spaceId, tabId) ->
        AlertDialog(onDismissRequest = { if (!renameBusy) renaming = null },
            title = { Text(stringResource(R.string.rename)) },
            text = { Column {
                OutlinedTextField(renameValue, { renameValue = it }, enabled = !renameBusy, singleLine = true,
                    label = { Text(stringResource(R.string.rename_name)) }, placeholder = { Text(renamePlaceholder) }, supportingText = { Text(stringResource(R.string.rename_hint)) })
                renameError?.let { Text(stringResource(renameErrorResource(it.kind)), color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !renameBusy, onClick = {
                if (!renameChanged(renameOriginal, renameValue)) { renaming = null; return@TextButton }
                renameBusy = true
                scope.launch {
                    try {
                        if (app.connections.statuses.value.none { it.host.id == hostId && it.state is ConnectionState.Connected })
                            throw io.herdrmobile.core.rpc.HcError("disconnected", "Disconnected")
                        val store = app.connections.workspace(hostId) ?: throw io.herdrmobile.core.rpc.HcError("disconnected", "Disconnected")
                        if (tabId == null) store.renameSpace(spaceId, renameValue) else store.renameTab(spaceId, tabId, renameValue)
                        renaming = null
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { renameError = e.asHcError() }
                    finally { renameBusy = false }
                }
            }) { Text(stringResource(R.string.rename)) } },
            dismissButton = { TextButton(enabled = !renameBusy, onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } })
    }
    trust?.let { request -> AlertDialog(onDismissRequest = { app.connections.answerTrust(request, false) }, title = { Text(stringResource(R.string.trust_title)) }, text = { Text(stringResource(R.string.trust_body, request.host.name, request.host.host, request.host.port, request.fingerprint)) }, confirmButton = { TextButton(onClick = { app.connections.answerTrust(request, true) }) { Text(stringResource(R.string.accept)) } }, dismissButton = { TextButton(onClick = { app.connections.answerTrust(request, false) }) { Text(stringResource(R.string.reject)) } }) }
}

@Composable
private fun DrawerAgent(row: ConsoleTerminal, selected: Boolean, subtitle: String, unread: Boolean, open: () -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().alpha(if (row.stale) 0.45f else 1f).clickable(enabled = !row.stale, onClick = open).padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgramAvatar(AgentDetector.detect(row.terminal)?.kind)
            Column(Modifier.weight(1f)) {
                Text(row.terminal.title.ifBlank { row.terminal.name ?: row.terminal.id }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            AgentMark(row.terminal.status?.state, unread)
        }
    }
}

@Composable
private fun AgentMark(state: String?, unread: Boolean = false) {
    if (state == "blocked" || state == "error") Text("?", color = MaterialTheme.colorScheme.error)
    else if (state == "working" || (state == "done" && unread)) Box(Modifier.size(7.dp).background(statusColor(state), CircleShape))
}

@Composable
private fun NotificationPermissionAction() {
    val context = LocalContext.current
    var explain by remember { mutableStateOf(false) }
    IconButton(onClick = { explain = true }) { Icon(Icons.Default.Notifications, stringResource(R.string.notifications)) }
    if (explain) AlertDialog(onDismissRequest = { explain = false }, title = { Text(stringResource(R.string.notifications)) },
        text = { Text(stringResource(R.string.notification_permission_help)) },
        confirmButton = { TextButton(onClick = {
            explain = false
            val activity = context as? MainActivity
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && activity != null) {
                activity.enableNotifications()
            } else {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }) { Text(stringResource(R.string.notification_enable)) } },
        dismissButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.cancel)) } })
}
