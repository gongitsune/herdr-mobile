package io.herdrmobile.android.notifications

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import io.herdrmobile.android.MainActivity
import io.herdrmobile.android.R
import io.herdrmobile.android.connection.ConsoleTerminal
import io.herdrmobile.android.connection.HostConnections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** No background service: notifications are emitted only while an Activity is resumed. */
class TerminalNotifications(private val application: Application, connections: HostConnections, scope: CoroutineScope) : Application.ActivityLifecycleCallbacks {
    private val foreground = MutableStateFlow(false)
    private var resumed = 0
    private val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val transitions = StatusTransitions()
    init {
        application.registerActivityLifecycleCallbacks(this)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, application.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
        scope.launch {
            combine(connections.agents, connections.statuses, foreground) { rows, hosts, visible ->
                transitions.update(io.herdrmobile.android.connection.notificationAgents(rows), hosts, visible)
            }.collect { changes -> changes.forEach(::post) }
        }
    }
    private fun post(row: ConsoleTerminal) {
        if (!foreground.value || !manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 && application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val destination = TerminalDestination(row.hostId, row.terminal.id)
        val intent = Intent(application, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = destination.uri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(application, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val status = application.getString(when (row.terminal.status?.state) {
            "blocked" -> R.string.blocked; "done" -> R.string.done; else -> R.string.error
        })
        val title = row.terminal.title.ifBlank { row.terminal.name ?: row.terminal.id }
        val notification = Notification.Builder(application, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_terminal)
            .setContentTitle(application.getString(R.string.notification_title, status, title))
            .setContentText(listOfNotNull(row.hostName, row.spaceName, row.tabName).joinToString(" / "))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        try { manager.notify(destination.uri().toString(), 0, notification) }
        catch (_: SecurityException) { /* Permission may be revoked between the check and notify. */ }
    }
    override fun onActivityResumed(activity: Activity) { resumed++; foreground.value = true }
    override fun onActivityPaused(activity: Activity) { resumed = (resumed - 1).coerceAtLeast(0); foreground.value = resumed > 0 }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
    companion object { private const val CHANNEL = "terminal-status" }
}
