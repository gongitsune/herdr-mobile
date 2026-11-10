package io.herdrmobile.android.updates

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.herdrmobile.android.BuildConfig
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.chat.ChatMarkdown
import io.herdrmobile.android.ui.settings.SettingsGroupTitle
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun UpdateDialogs(vm: UpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.permissionReturned() }
    LifecycleResumeEffect(state.phase) {
        if (state.phase == UpdatePhase.WAITING_PERMISSION) vm.permissionReturned()
        onPauseOrDispose { }
    }
    val blockedMessage = stringResource(R.string.update_install_blocked)
    val message = state.message?.let { stringResource(messageResource(it)) }
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }
    if (state.phase == UpdatePhase.WAITING_PERMISSION) AlertDialog(
        onDismissRequest = vm::cancelInstallPermission,
        title = { Text(stringResource(R.string.update_install_permission)) },
        text = { Text(stringResource(R.string.update_permission_help)) },
        confirmButton = { TextButton(onClick = {
            try {
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
            } catch (_: Exception) {
                Toast.makeText(context, blockedMessage, Toast.LENGTH_LONG).show()
                vm.cancelInstallPermission()
            }
        }) { Text(stringResource(R.string.settings)) } },
        dismissButton = { TextButton(onClick = vm::cancelInstallPermission) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(vm: UpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    // ListItem and SettingsGroupTitle already provide their own 16dp horizontal inset.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.update_current, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
        state.available?.let { update ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.update_available, update.release.tag), style = MaterialTheme.typography.titleMedium)
                    update.release.publishedAt?.let { date ->
                        val formatted = remember(date) {
                            runCatching { OffsetDateTime.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrDefault(date)
                        }
                        Text(stringResource(R.string.update_published, formatted), style = MaterialTheme.typography.bodySmall)
                    }
                    update.shortSha?.let { Text(stringResource(R.string.update_commit, it), style = MaterialTheme.typography.bodySmall) }
                    when (state.phase) {
                        UpdatePhase.DOWNLOADING -> {
                            LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / update.asset.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.update_progress, state.downloadedBytes * 100 / update.asset.size))
                            TextButton(onClick = vm::cancelDownload) { Text(stringResource(R.string.cancel)) }
                        }
                        UpdatePhase.INSTALLING -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.update_installing))
                        }
                        UpdatePhase.WAITING_PERMISSION -> Text(stringResource(R.string.update_permission_help))
                        UpdatePhase.IDLE -> Button(onClick = vm::downloadAndInstall, enabled = !state.checking) {
                            Text(stringResource(R.string.update_download_install))
                        }
                    }
                    Text(stringResource(R.string.update_release_notes), style = MaterialTheme.typography.titleSmall)
                    ChatMarkdown(update.release.body?.takeIf { it.isNotBlank() } ?: stringResource(R.string.update_no_notes))
                }
            }
        }
        SettingsGroupTitle(stringResource(R.string.settings))
        Text(stringResource(R.string.update_channel), Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            UpdateChannel.entries.forEachIndexed { index, channel ->
                SegmentedButton(selected = state.preferences.channel == channel,
                    onClick = { vm.setChannel(channel) }, enabled = state.loaded && state.phase == UpdatePhase.IDLE,
                    shape = SegmentedButtonDefaults.itemShape(index, UpdateChannel.entries.size)) {
                    Text(stringResource(if (channel == UpdateChannel.STABLE) R.string.update_stable else R.string.update_nightly))
                }
            }
        }
        ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.update_check_startup)) }, trailingContent = {
            Switch(checked = state.preferences.checkOnStartup, onCheckedChange = vm::setCheckOnStartup, enabled = state.loaded)
        })
        OutlinedButton(onClick = { vm.check() }, modifier = Modifier.padding(horizontal = 16.dp),
            enabled = state.loaded && !state.checking && state.phase == UpdatePhase.IDLE) {
            if (state.checking) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            Text(stringResource(if (state.checking) R.string.update_checking else R.string.update_check_now))
        }
    }
}

private fun messageResource(message: UpdateMessage): Int = when (message) {
    UpdateMessage.UP_TO_DATE -> R.string.update_up_to_date
    UpdateMessage.CHECK_FAILED -> R.string.update_check_failed
    UpdateMessage.RATE_LIMITED -> R.string.update_rate_limited
    UpdateMessage.DOWNLOAD_FAILED -> R.string.update_download_failed
    UpdateMessage.INSTALL_FAILED -> R.string.update_install_failed
    UpdateMessage.INSTALL_CONFLICT -> R.string.update_install_conflict
    UpdateMessage.INSTALL_INVALID -> R.string.update_install_invalid
    UpdateMessage.INSTALL_STORAGE -> R.string.update_install_storage
    UpdateMessage.INSTALL_BLOCKED -> R.string.update_install_blocked
    UpdateMessage.INSTALL_CANCELLED -> R.string.update_install_cancelled
    UpdateMessage.INSTALL_SUCCESS -> R.string.update_install_success
    UpdateMessage.SETTINGS_FAILED -> R.string.update_settings_failed
}
