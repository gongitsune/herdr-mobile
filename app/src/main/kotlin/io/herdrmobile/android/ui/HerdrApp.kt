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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
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
                host = null; terminal = null
                preferences.edit { remove("host"); remove("terminal") }
                drawer.open()
            }
        }
    }
    var files by remember { mutableStateOf<Pair<String, Space>?>(null) }
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
                host = null; terminal = null
                preferences.edit { remove("host"); remove("terminal") }
                drawer.open()
            }
        }
    }
    val trust by app.connections.trust.collectAsStateWithLifecycle()
    val pending by pendingDestination.collectAsStateWithLifecycle()
    val hostsVm: HostsViewModel = viewModel(factory = factory { HostsViewModel(app.hosts, app.connections) })
    fun open(hostId: String, terminalId: String) {
        host = hostId; terminal = terminalId; settings = false
        preferences.edit { putString("host", hostId); putString("terminal", terminalId) }
        scope.launch { drawer.close() }
    }
    LaunchedEffect(pending) { pending?.let { open(it.hostId, it.terminalId); consumed() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        BackHandler(enabled = settings || (!wide && drawer.isOpen)) { if (settings) settings = false else scope.launch { drawer.close() } }
        val branches = rememberWorkspaceBranches(app.connections, workspaces, wide || drawer.isOpen)
        val drawerContent: @Composable () -> Unit = {
            Column(Modifier.width(300.dp).fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    if (statuses.isEmpty()) IconButton(onClick = { hostsVm.edit(null); settings = true; scope.launch { drawer.close() } }) { Icon(Icons.Default.Add, stringResource(R.string.add_host)) }
                    statuses.forEach { status ->
                        var expanded by rememberSaveable(status.host.id) { mutableStateOf(true) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Computer, null, Modifier.size(20.dp))
                            Text(status.host.name, Modifier.weight(1f).padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.size(7.dp).background(if (status.state is ConnectionState.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, CircleShape))
                            IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, stringResource(R.string.collapse)) }
                            IconButton(onClick = { hostsVm.edit(null); settings = true; scope.launch { drawer.close() } }) { Icon(Icons.Default.Add, stringResource(R.string.add_host)) }
                        }
                        if (expanded) workspaces.filter { it.hostId == status.host.id }.flatMap { it.spaces }.forEach { space ->
                            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.FolderOpen, null, Modifier.size(20.dp))
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(space.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    branches[status.host.id to space.id]?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                IconButton(onClick = { files = status.host.id to space }) { Icon(Icons.Default.MoreVert, stringResource(R.string.workspace_panel)) }
                            }
                            val rows = space.tabs.flatMap { it.panes }.filter { it.agent != null }.mapNotNull { pane ->
                                agents.find { it.hostId == status.host.id && it.terminal.id == pane.terminal.id }
                            }
                            rows.forEachIndexed { index, row ->
                                DrawerAgent(row, host == row.hostId && terminal == row.terminal.id, branches[status.host.id to space.id] ?: space.name, true, unread(row), index == rows.lastIndex) { open(row.hostId, row.terminal.id) }
                            }
                        }
                    }
                }
                var allAgents by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { allAgents = !allAgents }, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (allAgents) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null)
                    Text("${stringResource(R.string.agents)} ${agents.size}", Modifier.weight(1f))
                }
                if (allAgents) Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    agents.sortedBy { it.terminal.priority }.forEach { row -> DrawerAgent(row, host == row.hostId && terminal == row.terminal.id, row.hostName, false, unread(row)) { open(row.hostId, row.terminal.id) } }
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
                TerminalScreen(checkNotNull(selectedVm), showMenu = !wide, tabs = {
                    val space = workspaces.filter { it.hostId == host }.flatMap { it.spaces }.find { space -> space.tabs.any { tab -> tab.panes.any { it.terminal.id == terminal || it.subAgents.any { child -> child.terminal.id == terminal } } } }
                    space?.let {
                        val index = space.tabs.indexOfFirst { tab -> tab.panes.any { it.terminal.id == terminal || it.subAgents.any { child -> child.terminal.id == terminal } } }.coerceAtLeast(0)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        PrimaryScrollableTabRow(selectedTabIndex = index, edgePadding = 12.dp, modifier = Modifier.weight(1f)) {
                            space.tabs.forEachIndexed { i, tab ->
                                val pane = tab.panes.firstOrNull { it.agent != null } ?: tab.panes.firstOrNull()
                                Tab(selected = i == index, enabled = pane != null, onClick = { pane?.let { open(host!!, it.terminal.id) } }, text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { AgentMark(tab.panes.mapNotNull { it.terminal.status?.state }.minByOrNull { when (it) { "blocked", "error" -> 0; "working" -> 1; "done" -> 2; else -> 3 } }, tab.panes.any { pane -> agents.any { row -> row.hostId == host && row.terminal.id == pane.terminal.id && unread(row) } }); Text(tab.name, maxLines = 1) } })
                            }
                        }
                        IconButton(onClick = { creating = host!! to space }) { Icon(Icons.Default.Add, stringResource(R.string.new_terminal)) }
                        }
                    }
                    val children = workspaces.filter { it.hostId == host }.flatMap { it.spaces }.flatMap { it.tabs }.flatMap { it.panes }.find { it.terminal.id == terminal }?.subAgents.orEmpty()
                    if (children.isNotEmpty()) Row(Modifier.fillMaxWidth()) { children.forEach { child -> TextButton(onClick = { open(host!!, child.terminal.id) }) { Text(child.terminal.title.ifBlank { child.terminal.name ?: child.id }, maxLines = 1) } } }
                }) { scope.launch { if (drawer.isOpen) drawer.close() else drawer.open() } }
            } else Scaffold(topBar = { TopAppBar(title = {}, navigationIcon = { if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, stringResource(R.string.open_menu)) } }) }) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.select_agent)) } }
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
    creating?.let { (hostId, space) -> NewTerminalSheet(app.connections, hostId, space, { creating = null }) { id -> creating = null; open(hostId, id) } }
    trust?.let { request -> AlertDialog(onDismissRequest = { app.connections.answerTrust(request, false) }, title = { Text(stringResource(R.string.trust_title)) }, text = { Text(stringResource(R.string.trust_body, request.host.name, request.host.host, request.host.port, request.fingerprint)) }, confirmButton = { TextButton(onClick = { app.connections.answerTrust(request, true) }) { Text(stringResource(R.string.accept)) } }, dismissButton = { TextButton(onClick = { app.connections.answerTrust(request, false) }) { Text(stringResource(R.string.reject)) } }) }
}

@Composable
private fun DrawerAgent(row: ConsoleTerminal, selected: Boolean, subtitle: String, tree: Boolean, unread: Boolean, lastChild: Boolean = false, open: () -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().alpha(if (row.stale) 0.45f else 1f).clickable(enabled = !row.stale, onClick = open).padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tree) {
                val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.width(16.dp).height(40.dp)) {
                    val x = 4.dp.toPx()
                    val middle = size.height / 2
                    val stroke = 1.5.dp.toPx()
                    drawLine(lineColor, Offset(x, 0f), Offset(x, if (lastChild) middle else size.height), stroke)
                    drawLine(lineColor, Offset(x, middle), Offset(size.width, middle), stroke)
                }
            }
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
