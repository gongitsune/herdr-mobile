package io.herdrmobile.android

import android.app.Application
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.android.notifications.TerminalNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HerdrApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val hosts by lazy { HostsRepository(this) }
    val connections by lazy { HostConnections(hosts, appScope) }
    private lateinit var notifications: TerminalNotifications
    override fun onCreate() {
        super.onCreate()
        notifications = TerminalNotifications(this, connections, appScope)
    }
}
