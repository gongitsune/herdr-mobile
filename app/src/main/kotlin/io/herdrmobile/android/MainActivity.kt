package io.herdrmobile.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import io.herdrmobile.android.notifications.TerminalDestination
import io.herdrmobile.android.ui.HerdrApp
import io.herdrmobile.android.ui.theme.HerdrTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val destination = MutableStateFlow<TerminalDestination?>(null)
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readDestination(intent)
        val app = application as HerdrApplication
        setContent { HerdrTheme { HerdrApp(app, destination) { destination.value = null } } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDestination(intent)
    }
    private fun readDestination(intent: Intent) {
        TerminalDestination.from(intent.data)?.let { destination.value = it }
        intent.data = null
    }
    fun enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
