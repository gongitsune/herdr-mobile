package io.herdrmobile.android.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.herdrmobile.android.BuildConfig
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.hosts.HostsScreen
import io.herdrmobile.android.ui.hosts.HostsViewModel
import io.herdrmobile.android.updates.UpdateChannel
import io.herdrmobile.android.updates.UpdateScreen
import io.herdrmobile.android.updates.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(page: SettingsPage, hostsVm: HostsViewModel, updates: UpdateViewModel,
                   navigate: (SettingsPage) -> Unit, back: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(when (page) {
                SettingsPage.TOP -> R.string.settings
                SettingsPage.HOSTS -> R.string.settings_hosts
                SettingsPage.NOTIFICATIONS -> R.string.notifications
                SettingsPage.UPDATES -> R.string.updates
                SettingsPage.ABOUT -> R.string.settings_about
                SettingsPage.LICENSES -> R.string.settings_licenses
            })) }, navigationIcon = {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            })
        },
    ) { padding ->
        // Reset scroll state when moving between pages.
        key(page) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (page) {
                    SettingsPage.TOP -> SettingsTop(hostsVm, updates, navigate)
                    SettingsPage.HOSTS -> HostsScreen(hostsVm)
                    SettingsPage.NOTIFICATIONS -> NotificationSettings()
                    SettingsPage.UPDATES -> UpdateScreen(updates)
                    SettingsPage.ABOUT -> AboutSettings(navigate)
                    SettingsPage.LICENSES -> LicenseSettings()
                }
            }
        }
    }
}

@Composable
private fun SettingsTop(hostsVm: HostsViewModel, updates: UpdateViewModel, navigate: (SettingsPage) -> Unit) {
    val hosts by hostsVm.hosts.collectAsStateWithLifecycle()
    val update by updates.state.collectAsStateWithLifecycle()
    val notificationsEnabled = rememberNotificationsEnabled()
    val channel = stringResource(if (update.preferences.channel == UpdateChannel.STABLE) R.string.update_stable else R.string.update_nightly)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsGroupTitle(stringResource(R.string.settings_connection))
        SettingsLink(Icons.Default.Computer, stringResource(R.string.settings_hosts), summaryText(hostSettingsSummary(hosts.size))) { navigate(SettingsPage.HOSTS) }
        SettingsGroupTitle(stringResource(R.string.notifications))
        SettingsLink(Icons.Default.Notifications, stringResource(R.string.notifications),
            stringResource(if (notificationsEnabled) R.string.notification_allowed else R.string.notification_off)) { navigate(SettingsPage.NOTIFICATIONS) }
        SettingsGroupTitle(stringResource(R.string.settings_app))
        SettingsLink(Icons.Default.SystemUpdate, stringResource(R.string.updates),
            summaryText(updateSettingsSummary(BuildConfig.VERSION_NAME, channel, update.available?.release?.tag)),
            badge = update.available != null) { navigate(SettingsPage.UPDATES) }
        SettingsLink(Icons.Default.Info, stringResource(R.string.settings_about),
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) { navigate(SettingsPage.ABOUT) }
    }
}

@Composable
private fun summaryText(summary: SettingsSummary): String = stringResource(summary.resource, *summary.arguments.toTypedArray())

@Composable
fun SettingsGroupTitle(title: String) {
    Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingsLink(icon: ImageVector, title: String, description: String, badge: Boolean = false, open: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { BadgedBox(badge = { if (badge) Badge() }) { Icon(icon, null) } },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        modifier = Modifier.clickable(onClick = open),
    )
}

@Composable
private fun rememberNotificationsEnabled(permissionResult: Int = 0): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(permissionResult) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    return enabled
}

@Composable
private fun NotificationSettings() {
    val context = LocalContext.current
    var permissionResult by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionResult++ }
    val enabled = rememberNotificationsEnabled(permissionResult)
    fun openSystemSettings() = openSettingsIntent(context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(if (enabled) R.string.notification_allowed else R.string.notification_off), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.notification_permission_help))
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Button(onClick = {
                val preferences = context.getSharedPreferences("notification-permission", Context.MODE_PRIVATE)
                val activity = context as? Activity
                if (activity == null || (preferences.getBoolean("asked", false) &&
                        !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
                    openSystemSettings()
                } else {
                    preferences.edit { putBoolean("asked", true) }
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }) { Text(stringResource(R.string.notification_allow)) }
        }
        OutlinedButton(onClick = {
            openSystemSettings()
        }) { Text(stringResource(R.string.notification_system_settings)) }
    }
}

@Composable
private fun AboutSettings(navigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(context.applicationInfo.loadLabel(context.packageManager).toString(), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.update_current, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        }
        SettingsLink(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.settings_repository), "gongitsune/herdr-mobile") {
            openSettingsIntent(context, Intent(Intent.ACTION_VIEW, "https://github.com/gongitsune/herdr-mobile".toUri()))
        }
        SettingsLink(Icons.Default.Description, stringResource(R.string.settings_licenses), stringResource(R.string.settings_licenses_description)) {
            navigate(SettingsPage.LICENSES)
        }
    }
}

@Composable
private fun LicenseSettings() {
    val context = LocalContext.current
    val failure = stringResource(R.string.settings_licenses_failed)
    val notices by produceState<String?>(null, context, failure) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("third_party_notices.txt").bufferedReader().use { it.readText() } }
                .getOrElse { failure }
        }
    }
    if (notices == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else SelectionContainer {
        Text(notices.orEmpty(), Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun openSettingsIntent(context: Context, intent: Intent) {
    try { context.startActivity(intent) }
    catch (_: Exception) { Toast.makeText(context, R.string.settings_open_failed, Toast.LENGTH_LONG).show() }
}
