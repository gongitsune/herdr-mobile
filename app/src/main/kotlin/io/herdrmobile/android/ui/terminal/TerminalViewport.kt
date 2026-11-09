package io.herdrmobile.android.ui.terminal

import android.view.View
import androidx.compose.foundation.layout.*
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
import io.herdrmobile.android.ui.theme.LocalHerdrDark

/** Retained underneath Chat: surface selection never owns the attach lifetime. */
@Composable
fun TerminalViewport(vm: TerminalViewModel, modifier: Modifier = Modifier, visible: Boolean = true, retry: Int = 0, tail: Boolean = false, onTap: (() -> Unit)? = null) {
    val observe by vm.observe.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf<TerminalView?>(null) }
    val dark = LocalHerdrDark.current
    if (vm.demo) {
        if (visible) Surface(modifier) { Column(Modifier.padding(16.dp)) { Text(stringResource(R.string.workspace_demo)); Text("$ ./gradlew :app:assembleDebug\nBUILD SUCCESSFUL", style = MaterialTheme.typography.bodySmall) } }
        return
    }
    key(observe, retry, tail) {
        AndroidView(factory = { context -> (if (tail) TailTerminalView(context) else TerminalView(context, null)).also { view = it } }, update = {
            it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            it.isFocusable = visible && !tail; it.isFocusableInTouchMode = visible && !tail
            if (tail) { it.contentDescription = it.context.getString(R.string.live_terminal); it.setOnClickListener { onTap?.invoke() } }
            if (!visible) it.clearFocus()
        }, modifier = modifier)
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
    LaunchedEffect(dark, view) { vm.applyPalette(dark) }
}
