package io.herdrmobile.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.herdrmobile.android.notifications.TerminalDestination
import io.herdrmobile.android.ui.HerdrApp
import io.herdrmobile.android.ui.theme.HerdrTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val destination = MutableStateFlow<TerminalDestination?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
}
