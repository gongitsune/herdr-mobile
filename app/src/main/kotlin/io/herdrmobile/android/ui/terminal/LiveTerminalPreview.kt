package io.herdrmobile.android.ui.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.factory

@Composable
fun LiveTerminalPreview(parent: TerminalViewModel, modifier: Modifier = Modifier, tail: Boolean = true, onTap: () -> Unit = {}) {
    val store = remember(parent) { ViewModelStore() }
    val owner = remember(store) { object : ViewModelStoreOwner { override val viewModelStore = store } }
    val preview: TerminalViewModel = viewModel(viewModelStoreOwner = owner, factory = factory { parent.newObservePreview() })
    DisposableEffect(store) { onDispose { store.clear() } }
    TerminalViewport(preview, modifier, tail = tail, onTap = onTap)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalPeek(parent: TerminalViewModel, dismiss: () -> Unit, fullTerminal: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.peek_read_only), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            LiveTerminalPreview(parent, Modifier.fillMaxWidth().height(340.dp), tail = false)
            TextButton(onClick = { dismiss(); fullTerminal() }) { Text(stringResource(R.string.open_terminal)) }
        }
    }
}
