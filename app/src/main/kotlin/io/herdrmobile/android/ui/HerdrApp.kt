package io.herdrmobile.android.ui

import android.net.Uri
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import io.herdrmobile.android.MainActivity
import io.herdrmobile.android.notifications.TerminalDestination
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Layers
import io.herdrmobile.android.ui.spaces.*
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import io.herdrmobile.android.HerdrApplication
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.console.*
import io.herdrmobile.android.ui.hosts.*
import io.herdrmobile.android.ui.terminal.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HerdrApp(app: HerdrApplication, pendingDestination: StateFlow<TerminalDestination?>, consumed: () -> Unit) {
    val nav = rememberNavController()
    val destination by pendingDestination.collectAsStateWithLifecycle()
    LaunchedEffect(destination) {
        destination?.let { target ->
            nav.navigate("terminal/${Uri.encode(target.hostId)}/${Uri.encode(target.terminalId)}") { launchSingleTop = true }
            consumed()
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val trust by app.connections.trust.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
        NotificationPermissionAction()
    }) },
        bottomBar = { if (route?.startsWith("terminal/") != true) NavigationBar {
            NavigationBarItem(selected = route == "console", onClick = { nav.navigate("console") { popUpTo("console"); launchSingleTop = true } },
                icon = { Icon(Icons.Default.Terminal, null) }, label = { Text(stringResource(R.string.agents)) })
            NavigationBarItem(selected = route == "spaces" || route?.startsWith("space/") == true, onClick = { nav.navigate("spaces") { popUpTo("console"); launchSingleTop = true } },
                icon = { Icon(Icons.Default.Layers, null) }, label = { Text(stringResource(R.string.spaces)) })
            NavigationBarItem(selected = route == "hosts", onClick = { nav.navigate("hosts") { popUpTo("console"); launchSingleTop = true } },
                icon = { Icon(Icons.Default.Dns, null) }, label = { Text(stringResource(R.string.hosts)) })
        } }) { padding ->
        NavHost(navController = nav, startDestination = "console", modifier = Modifier.padding(padding)) {
            composable("console") {
                val vm: ConsoleViewModel = viewModel(factory = factory { ConsoleViewModel(app.connections) })
                var peek by remember { mutableStateOf<io.herdrmobile.android.connection.ConsoleTerminal?>(null) }
                ConsoleScreen(vm, { peek = it }) { host, terminal -> nav.navigate("terminal/${Uri.encode(host)}/${Uri.encode(terminal)}") }
                peek?.let { row -> PeekSheet(app.connections, row, { peek = null }) { nav.navigate("terminal/${Uri.encode(row.hostId)}/${Uri.encode(row.terminal.id)}") } }
            }
            composable("spaces") {
                val vm: SpacesViewModel = viewModel(factory = factory { SpacesViewModel(app.connections) })
                SpacesScreen(vm) { host, space -> nav.navigate("space/${Uri.encode(host)}/${Uri.encode(space)}") }
            }
            composable("space/{host}/{space}") { target ->
                val vm: SpacesViewModel = viewModel(viewModelStoreOwner = remember(target) { nav.getBackStackEntry("spaces") }, factory = factory { SpacesViewModel(app.connections) })
                SpaceDetailScreen(vm, target.arguments?.getString("host").orEmpty(), target.arguments?.getString("space").orEmpty(), app.connections, { nav.popBackStack() }) { host, terminal ->
                    nav.navigate("terminal/${Uri.encode(host)}/${Uri.encode(terminal)}")
                }
            }
            composable("hosts") {
                val vm: HostsViewModel = viewModel(factory = factory { HostsViewModel(app.hosts, app.connections) })
                HostsScreen(vm)
            }
            composable("terminal/{host}/{terminal}") { destination ->
                val host = destination.arguments?.getString("host").orEmpty()
                val terminal = destination.arguments?.getString("terminal").orEmpty()
                val vm: TerminalViewModel = viewModel(factory = factory { TerminalViewModel(app.connections, host, terminal) })
                TerminalScreen(vm) { nav.popBackStack() }
            }
        }
    }
    trust?.let { request ->
        AlertDialog(onDismissRequest = { app.connections.answerTrust(request, false) }, title = { Text(stringResource(R.string.trust_title)) },
            text = { Text(stringResource(R.string.trust_body, request.host.name, request.host.host, request.host.port, request.fingerprint)) },
            confirmButton = { TextButton(onClick = { app.connections.answerTrust(request, true) }) { Text(stringResource(R.string.accept)) } },
            dismissButton = { TextButton(onClick = { app.connections.answerTrust(request, false) }) { Text(stringResource(R.string.reject)) } })
    }
}

@Composable
private fun NotificationPermissionAction() {
    val context = LocalContext.current
    var explain by remember { mutableStateOf(false) }
    TextButton(onClick = { explain = true }) { Text(stringResource(R.string.notifications)) }
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
