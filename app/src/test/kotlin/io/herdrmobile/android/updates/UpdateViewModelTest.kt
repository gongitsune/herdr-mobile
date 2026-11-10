package io.herdrmobile.android.updates

import androidx.lifecycle.ViewModelStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val settings = FakeSettings()
    private val network = FakeNetwork()
    private val installer = FakeInstaller()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain(); network.file?.delete() }
    private fun model(now: Long = CHECK_INTERVAL_MS + 100, claim: () -> Boolean = { true }): UpdateViewModel =
        UpdateViewModel(settings, network, installer, "0.1.0", 10, { now }, claim).also { store.put("updates", it) }

    @Test fun startupChecksOnceAndThrottlePersistsForFailures() = runTest(dispatcher) {
        network.error = IOException("offline")
        val vm = model()
        runCurrent()
        assertEquals(listOf(UpdateChannel.STABLE), network.calls)
        assertNull(vm.state.value.message)
        assertEquals(CHECK_INTERVAL_MS + 100, settings.preferences.value.lastChecks[UpdateChannel.STABLE])
        vm.setCheckOnStartup(false); vm.setCheckOnStartup(true)
        runCurrent()
        assertEquals(1, network.calls.size)
        model(); runCurrent() // new Activity/ViewModel, same saved timestamp
        assertEquals(1, network.calls.size)
    }
    @Test fun disabledStartupAndProcessClaimDoNotCheck() = runTest(dispatcher) {
        settings.preferences.value = UpdatePreferences(checkOnStartup = false)
        val vm = model(); runCurrent()
        assertTrue(network.calls.isEmpty())
        vm.check(); runCurrent() // manual ignores disabled startup and throttle
        assertEquals(1, network.calls.size)
        settings.preferences.value = UpdatePreferences()
        model(claim = { false }); runCurrent()
        assertEquals(1, network.calls.size)
    }
    @Test fun manualErrorsAreVisibleAndAutomaticErrorsAreSilent() = runTest(dispatcher) {
        val vm = model(); runCurrent()
        for (status in listOf(403, 429, 500)) {
            network.error = UpdateHttpException(status)
            vm.check(); runCurrent()
            assertEquals(if (status == 500) UpdateMessage.CHECK_FAILED else UpdateMessage.RATE_LIMITED, vm.state.value.message)
            assertFalse(vm.state.value.checking)
            vm.clearMessage(); vm.check(manual = false); runCurrent()
            assertNull(vm.state.value.message)
        }
    }
    @Test fun channelChangeClearsOldOfferAndChecksSelectedChannel() = runTest(dispatcher) {
        val vm = model(); runCurrent()
        assertNotNull(vm.state.value.available)
        vm.setChannel(UpdateChannel.NIGHTLY); runCurrent()
        assertEquals(UpdateChannel.NIGHTLY, vm.state.value.preferences.channel)
        assertNull(vm.state.value.available)
        vm.check(); runCurrent()
        assertEquals(UpdateChannel.NIGHTLY, network.calls.last())
        assertEquals("abcdef1", vm.state.value.available?.shortSha)
    }
    @Test fun switchingChannelCancelsInFlightCheckWithoutStaleOffer() = runTest(dispatcher) {
        settings.preferences.value = UpdatePreferences(checkOnStartup = false)
        network.pending = CompletableDeferred()
        val vm = model(); runCurrent()
        vm.check(); runCurrent()
        vm.setChannel(UpdateChannel.NIGHTLY); runCurrent()
        network.pending!!.complete(stableRelease()); runCurrent()
        assertNull(vm.state.value.available)
        assertFalse(vm.state.value.checking)
    }
    @Test fun noNewVersionHasManualMessageOnly() = runTest(dispatcher) {
        network.stable = stableRelease("v0.1.0")
        val vm = model(); runCurrent()
        assertNull(vm.state.value.message)
        assertNull(vm.state.value.available)
        vm.check(); runCurrent()
        assertEquals(UpdateMessage.UP_TO_DATE, vm.state.value.message)
    }
    @Test fun downloadWaitsForPermissionThenInstallsAndReportsConflict() = runTest(dispatcher) {
        installer.allowed = false
        val vm = model(); runCurrent()
        vm.downloadAndInstall(); runCurrent()
        assertEquals(UpdatePhase.WAITING_PERMISSION, vm.state.value.phase)
        assertEquals(123L, vm.state.value.downloadedBytes)
        assertEquals(0, installer.installs)
        vm.permissionReturned(); runCurrent()
        assertEquals(UpdatePhase.WAITING_PERMISSION, vm.state.value.phase)
        installer.allowed = true
        vm.permissionReturned(); runCurrent()
        assertEquals(1, installer.installs)
        assertEquals(UpdatePhase.INSTALLING, vm.state.value.phase)
        installer.results.emit(InstallResult.CONFLICT); runCurrent()
        assertEquals(UpdateMessage.INSTALL_CONFLICT, vm.state.value.message)
        assertEquals(UpdatePhase.IDLE, vm.state.value.phase)
        assertFalse(network.file!!.exists())
    }
    @Test fun cancelledDownloadsAndPermissionRequestsCleanUp() = runTest(dispatcher) {
        network.suspendDownload = true
        val vm = model(); runCurrent()
        vm.downloadAndInstall(); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, vm.state.value.phase)
        vm.cancelDownload(); runCurrent()
        assertTrue(network.downloadCancelled)
        assertEquals(UpdatePhase.IDLE, vm.state.value.phase)
        assertNull(vm.state.value.message)
        network.suspendDownload = false; installer.allowed = false
        vm.downloadAndInstall(); runCurrent()
        vm.cancelInstallPermission(); runCurrent()
        assertFalse(network.file!!.exists())
        assertEquals(UpdatePhase.IDLE, vm.state.value.phase)
    }
    @Test fun pendingPermissionSurvivesNewViewModelAndResumesOnlyOnce() = runTest(dispatcher) {
        installer.allowed = false
        var vm = model(); runCurrent()
        vm.downloadAndInstall(); runCurrent()
        assertNotNull(installer.pendingPermissionApk)
        vm = model(); runCurrent()
        assertEquals(UpdatePhase.WAITING_PERMISSION, vm.state.value.phase)
        installer.allowed = true
        vm.permissionReturned(); vm.permissionReturned(); runCurrent()
        assertEquals(1, installer.installs)
        installer.results.emit(InstallResult.SUCCESS); runCurrent()
        assertEquals(UpdateMessage.INSTALL_SUCCESS, vm.state.value.message)
        assertNull(installer.pendingPermissionApk)
    }
    @Test fun downloadAndInstallerFailuresCanBeRetried() = runTest(dispatcher) {
        val vm = model(); runCurrent()
        network.downloadError = IOException("size mismatch")
        vm.downloadAndInstall(); runCurrent()
        assertEquals(UpdateMessage.DOWNLOAD_FAILED, vm.state.value.message)
        assertEquals(UpdatePhase.IDLE, vm.state.value.phase)
        network.downloadError = null; installer.fail = true
        vm.downloadAndInstall(); runCurrent()
        assertEquals(UpdateMessage.INSTALL_FAILED, vm.state.value.message)
        installer.fail = false
        vm.downloadAndInstall(); runCurrent()
        assertEquals(UpdatePhase.INSTALLING, vm.state.value.phase)
        installer.results.emit(InstallResult.CANCELLED); runCurrent()
        assertEquals(UpdateMessage.INSTALL_CANCELLED, vm.state.value.message)
    }

    private class FakeSettings : UpdateSettings {
        override val preferences = MutableStateFlow(UpdatePreferences())
        override suspend fun setChannel(channel: UpdateChannel) { preferences.value = preferences.value.copy(channel = channel) }
        override suspend fun setCheckOnStartup(enabled: Boolean) { preferences.value = preferences.value.copy(checkOnStartup = enabled) }
        override suspend fun recordCheck(channel: UpdateChannel, at: Long) { preferences.value = preferences.value.copy(lastChecks = preferences.value.lastChecks + (channel to at)) }
    }
    private class FakeNetwork : UpdateNetwork {
        val calls = mutableListOf<UpdateChannel>()
        var error: Exception? = null
        var stable = stableRelease()
        var pending: CompletableDeferred<AppRelease>? = null
        var file: File? = null
        var downloadError: Exception? = null
        var suspendDownload = false
        var downloadCancelled = false
        override suspend fun release(channel: UpdateChannel): AppRelease {
            calls += channel
            error?.let { throw it }
            pending?.let { return it.await() }
            return if (channel == UpdateChannel.STABLE) stable else nightlyRelease()
        }
        override suspend fun download(asset: ReleaseAsset, progress: (Long) -> Unit): File {
            downloadError?.let { throw it }
            if (suspendDownload) try { awaitCancellation() } finally { downloadCancelled = true }
            progress(asset.size)
            return File.createTempFile("herdr-update-test", ".apk").also { file = it }
        }
    }
    private class FakeInstaller : UpdateInstaller {
        override val results = MutableSharedFlow<InstallResult>()
        override var pendingPermissionApk: File? = null
        override fun awaitPermission(apk: File) { pendingPermissionApk = apk }
        override fun clearPendingPermission() { pendingPermissionApk = null }
        var allowed = true
        var installs = 0
        var fail = false
        override fun canInstall() = allowed
        override suspend fun install(apk: File) { if (fail) throw IOException("installer failed"); installs++ }
    }
}
