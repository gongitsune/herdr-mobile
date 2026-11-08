package io.herdrmobile.android.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.view.TerminalView
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.terminalErrorMessage

@Composable
fun TerminalScreen(vm: TerminalViewModel, back: () -> Unit) {
    val observe by vm.observe.collectAsStateWithLifecycle()
    val control by vm.control.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val recovering by vm.recovering.collectAsStateWithLifecycle()
    val ended by vm.ended.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var confirmKill by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(if (observe) R.string.observe else R.string.terminal), Modifier.padding(12.dp))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.terminal_menu)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.interrupt)) }, enabled = ready && !observe && !busy, onClick = { menu = false; vm.interrupt() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.kill)) }, enabled = ready && !observe && !busy, onClick = { menu = false; confirmKill = true })
                    DropdownMenuItem(text = { Text(stringResource(if (observe) R.string.interactive else R.string.observe)) }, onClick = { menu = false; vm.observe.value = !observe })
                    DropdownMenuItem(text = { Text(stringResource(R.string.read_text)) }, enabled = !busy, onClick = { menu = false; vm.read() })
                }
            }
        }
        if (error != null || ended || recovering) Row(Modifier.padding(horizontal = 16.dp)) {
            Text(error?.let { terminalErrorMessage(it) } ?: stringResource(if (recovering) R.string.terminal_recovering else R.string.attach_closed), Modifier.weight(1f))
            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
        }
        key(observe, retry) {
            AndroidView(factory = { context -> TerminalView(context, null).also { view = it } }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(view, observe, retry, lifecycle) {
            val current = view
            var bound = false
            fun bind() { if (!bound && current != null) { bound = true; vm.bind(current) } }
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) bind()
                if (event == Lifecycle.Event.ON_STOP) { bound = false; vm.unbind() }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) bind()
            onDispose { lifecycle.removeObserver(observer); vm.unbind() }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(enabled = ready && !observe, onClick = { vm.keyCode(KeyEvent.KEYCODE_ESCAPE) }) { Text(stringResource(R.string.esc)) }
            TextButton(enabled = ready && !observe, onClick = { vm.keyCode(KeyEvent.KEYCODE_TAB) }) { Text(stringResource(R.string.terminal_tab)) }
            FilterChip(selected = control, enabled = !observe, onClick = { vm.control.value = !control }, label = { Text(stringResource(R.string.ctrl)) })
            IconButton(enabled = ready && !observe, onClick = { vm.keyCode(KeyEvent.KEYCODE_DPAD_UP) }) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.up)) }
            IconButton(enabled = ready && !observe, onClick = { vm.keyCode(KeyEvent.KEYCODE_DPAD_DOWN) }) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.down)) }
            IconButton(enabled = ready && !observe, onClick = { vm.keyCode(KeyEvent.KEYCODE_DPAD_LEFT) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.left)) }
            IconButton(enabled = ready && !observe, onClick = { vm.keyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.right)) }
            TextButton(enabled = ready && !observe, onClick = { vm.key("\u0003") }) { Text(stringResource(R.string.ctrl_c)) }
            TextButton(enabled = ready && !observe, onClick = { vm.keyCode(KeyEvent.KEYCODE_ENTER) }) { Text(stringResource(R.string.enter)) }
        }
        Row(Modifier.padding(8.dp)) {
            OutlinedTextField(draft, { vm.draft.value = it }, modifier = Modifier.weight(1f), enabled = !observe && !busy,
                label = { Text(stringResource(R.string.composer)) }, maxLines = 4)
            IconButton(enabled = ready && !observe && !busy && draft.isNotBlank(), onClick = vm::send) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send)) }
        }
    }
    if (confirmKill) AlertDialog(onDismissRequest = { confirmKill = false }, title = { Text(stringResource(R.string.kill)) },
        text = { Text(stringResource(R.string.kill_confirm)) },
        confirmButton = { TextButton(onClick = { confirmKill = false; vm.kill() }) { Text(stringResource(R.string.kill)) } },
        dismissButton = { TextButton(onClick = { confirmKill = false }) { Text(stringResource(R.string.cancel)) } })
    text?.let { value -> AlertDialog(onDismissRequest = { vm.text.value = null }, title = { Text(stringResource(R.string.read_text)) },
        text = { SelectionContainer { Text(value, Modifier.verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = { vm.text.value = null }) { Text(stringResource(R.string.close)) } }) }
}
