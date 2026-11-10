package io.herdrmobile.android

import android.app.Application
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.android.updates.AndroidUpdateInstaller
import io.herdrmobile.android.updates.GitHubUpdateNetwork
import io.herdrmobile.android.updates.UpdateSettingsStore
import io.herdrmobile.android.notifications.TerminalNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HerdrApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val updateSettings by lazy { UpdateSettingsStore(this) }
    val updateNetwork by lazy { GitHubUpdateNetwork(cacheDir, BuildConfig.VERSION_NAME) }
    val updateInstaller by lazy { AndroidUpdateInstaller(this) }
    private var updateStartupClaimed = false
    fun claimUpdateStartup(): Boolean {
        if (updateStartupClaimed) return false
        updateStartupClaimed = true
        return true
    }
    val hosts by lazy { HostsRepository(this) }
    val connections by lazy { HostConnections(hosts, appScope) }
    private lateinit var notifications: TerminalNotifications
    override fun onCreate() {
        super.onCreate()
        notifications = TerminalNotifications(this, connections, appScope)
    }
}
