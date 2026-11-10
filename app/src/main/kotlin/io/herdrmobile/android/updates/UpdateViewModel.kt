package io.herdrmobile.android.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Messages are translated by the UI; network and installer details never leak into the UI.
enum class UpdateMessage { UP_TO_DATE, CHECK_FAILED, RATE_LIMITED, DOWNLOAD_FAILED, INSTALL_FAILED, INSTALL_CONFLICT, INSTALL_INVALID, INSTALL_STORAGE, INSTALL_BLOCKED, INSTALL_CANCELLED, INSTALL_SUCCESS, SETTINGS_FAILED }
enum class UpdatePhase { IDLE, DOWNLOADING, WAITING_PERMISSION, INSTALLING }
data class UpdateUiState(
    val preferences: UpdatePreferences = UpdatePreferences(),
    val loaded: Boolean = false,
    val checking: Boolean = false,
    val available: AvailableUpdate? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val downloadedBytes: Long = 0,
    val message: UpdateMessage? = null,
)

class UpdateViewModel(
    private val settings: UpdateSettings,
    private val network: UpdateNetwork,
    private val installer: UpdateInstaller,
    private val versionName: String,
    private val versionCode: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val claimStartupCheck: () -> Boolean = { true },
) : ViewModel() {
    private var downloadedApk: File? = installer.pendingPermissionApk
    private val mutableState = MutableStateFlow(UpdateUiState(
        phase = if (downloadedApk != null) UpdatePhase.WAITING_PERMISSION else UpdatePhase.IDLE,
    ))
    val state = mutableState.asStateFlow()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var installJob: Job? = null
    private var startupHandled = false
    init {
        viewModelScope.launch {
            try {
                settings.preferences.collect { preferences ->
                    mutableState.update { it.copy(preferences = preferences, loaded = true) }
                    if (!startupHandled) {
                        startupHandled = true
                        if (claimStartupCheck() && shouldCheckOnStartup(preferences, now())) check(manual = false)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(message = UpdateMessage.SETTINGS_FAILED) } }
        }
        viewModelScope.launch {
            installer.results.collect { result ->
                downloadedApk?.delete(); downloadedApk = null
                installer.clearPendingPermission()
                installer.consumeResult()
                mutableState.update { it.copy(phase = UpdatePhase.IDLE, message = when (result) {
                    InstallResult.SUCCESS -> UpdateMessage.INSTALL_SUCCESS
                    InstallResult.CANCELLED -> UpdateMessage.INSTALL_CANCELLED
                    InstallResult.CONFLICT -> UpdateMessage.INSTALL_CONFLICT
                    InstallResult.INVALID -> UpdateMessage.INSTALL_INVALID
                    InstallResult.STORAGE -> UpdateMessage.INSTALL_STORAGE
                    InstallResult.BLOCKED -> UpdateMessage.INSTALL_BLOCKED
                    InstallResult.FAILED -> UpdateMessage.INSTALL_FAILED
                }) }
            }
        }
    }

    fun check(manual: Boolean = true) {
        if (checkJob?.isActive == true || state.value.phase != UpdatePhase.IDLE) return
        checkJob = viewModelScope.launch {
            mutableState.update { it.copy(checking = true, message = null) }
            try {
                val preferences = settings.preferences.first()
                // Failed requests are throttled too, including GitHub's anonymous rate limit.
                settings.recordCheck(preferences.channel, now())
                val release = network.release(preferences.channel)
                val update = findUpdate(release, preferences.channel, versionName, versionCode)
                mutableState.update { it.copy(available = update, message = if (manual && update == null) UpdateMessage.UP_TO_DATE else null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (manual) mutableState.update { it.copy(message = if (e is UpdateHttpException && e.status in listOf(403, 429)) UpdateMessage.RATE_LIMITED else UpdateMessage.CHECK_FAILED) }
            } finally { mutableState.update { it.copy(checking = false) } }
        }
    }

    fun setChannel(channel: UpdateChannel) {
        if (state.value.phase != UpdatePhase.IDLE) return
        checkJob?.cancel()
        mutableState.update { it.copy(available = null, message = null) }
        saveSetting { settings.setChannel(channel) }
    }
    fun setCheckOnStartup(enabled: Boolean) = saveSetting { settings.setCheckOnStartup(enabled) }
    private fun saveSetting(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(message = UpdateMessage.SETTINGS_FAILED) } }
        }
    }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    fun downloadAndInstall() {
        val update = state.value.available ?: return
        if (state.value.phase != UpdatePhase.IDLE || state.value.checking || downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            mutableState.update { it.copy(phase = UpdatePhase.DOWNLOADING, downloadedBytes = 0, message = null) }
            try {
                downloadedApk = network.download(update.asset) { bytes -> mutableState.update { it.copy(downloadedBytes = bytes) } }
                continueInstall()
            } catch (e: CancellationException) {
                downloadedApk?.delete(); downloadedApk = null
                mutableState.update { it.copy(phase = UpdatePhase.IDLE) }
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(phase = UpdatePhase.IDLE, message = UpdateMessage.DOWNLOAD_FAILED) }
            }
        }
    }
    fun cancelDownload() { downloadJob?.cancel() }
    fun cancelInstallPermission() {
        downloadedApk?.delete(); downloadedApk = null
        installer.clearPendingPermission()
        mutableState.update { it.copy(phase = UpdatePhase.IDLE) }
    }
    fun permissionReturned() {
        if (state.value.phase != UpdatePhase.WAITING_PERMISSION || installJob?.isActive == true) return
        installJob = viewModelScope.launch { continueInstall() }
    }
    private suspend fun continueInstall() {
        val apk = downloadedApk ?: return
        try {
            if (!installer.canInstall()) {
                installer.awaitPermission(apk)
                mutableState.update { it.copy(phase = UpdatePhase.WAITING_PERMISSION) }
                return
            }
            mutableState.update { it.copy(phase = UpdatePhase.INSTALLING) }
            installer.install(apk)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            apk.delete(); downloadedApk = null
            installer.clearPendingPermission()
            mutableState.update { it.copy(phase = UpdatePhase.IDLE, message = UpdateMessage.INSTALL_FAILED) }
        }
    }
}
