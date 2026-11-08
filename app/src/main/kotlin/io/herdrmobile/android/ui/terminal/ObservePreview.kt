package io.herdrmobile.android.ui.terminal

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.terminalErrorMessage
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.android.ui.factory

/** A preview owns its observe attach only while it is composed/visible. */
@Composable
fun ObservePreview(connections: HostConnections, hostId: String, terminalId: String, modifier: Modifier = Modifier) {
    val store = remember(hostId, terminalId) { ViewModelStore() }
    val owner = remember(store) { object : ViewModelStoreOwner { override val viewModelStore = store } }
    val vm: TerminalViewModel = viewModel(viewModelStoreOwner = owner, factory = factory { TerminalViewModel(connections, hostId, terminalId, initialObserve = true) })
    DisposableEffect(store) { onDispose { store.clear() } }
    val error by vm.error.collectAsStateWithLifecycle()
    val recovering by vm.recovering.collectAsStateWithLifecycle()
    Column(modifier) {
        error?.let { Text(terminalErrorMessage(it), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (recovering) LinearProgressIndicator(Modifier.fillMaxWidth())
        TerminalViewport(vm, Modifier.fillMaxWidth().weight(1f))
    }
}
