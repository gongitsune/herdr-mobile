package io.herdrmobile.android.ui.terminal

import android.util.TypedValue
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import io.herdrmobile.core.AttachSession
import io.herdrmobile.core.HcClient
import io.herdrmobile.core.model.CloseReason
import io.herdrmobile.core.rpc.HcError
import io.herdrmobile.android.ui.asHcError
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Owns one remote attach. No process, PTY or native library is created. Main thread only. */
class RemoteTerminalSession(
    private val view: TerminalView,
    private val client: HcClient,
    private val id: String,
    private val scope: CoroutineScope,
    private val observe: Boolean,
    control: () -> Boolean,
    alt: () -> Boolean,
    shift: () -> Boolean,
    consumeModifiers: () -> Unit,
    private val ready: (Boolean) -> Unit,
    private val failed: (HcError) -> Unit,
    private val closed: (CloseReason) -> Unit,
) {
    private sealed interface Input {
        data class Bytes(val bytes: ByteArray) : Input
        data class Size(val cols: Int, val rows: Int) : Input
    }
    private val inputs = Channel<Input>(256)
    private var columns = 80
    private var rows = 24
    private var active = false
    private var job: Job? = null
    private val callbacks = TerminalCallbacks(view, control, alt, shift, observe)
    val terminal = TerminalSession(object : TerminalSession.RemoteIO {
        override fun write(bytes: ByteArray) {
            consumeModifiers()
            if (!observe && active && inputs.trySend(Input.Bytes(bytes)).isFailure) { failed(HcError("input_overflow", "Remote input queue full")); dispose() }
        }
        override fun resize(cols: Int, newRows: Int) {
            columns = cols; rows = newRows
            if (!observe && active && inputs.trySend(Input.Size(cols, newRows)).isFailure) { failed(HcError("input_overflow", "Remote input queue full")); dispose() }
        }
        override fun close() = dispose()
    }, callbacks)

    init {
        terminal.updateSize(columns, rows, 8, 16)
        terminal.emulator.mColors.reset()
        view.setTerminalViewClient(callbacks)
        view.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, view.resources.displayMetrics).toInt())
        view.attachSession(terminal)
    }
    fun start() {
        if (job != null) return
        job = scope.launch {
            var attach: AttachSession? = null
            try {
                val session = client.attach(id, columns, rows, observe = observe)
                attach = session
                if (!observe) { session.resize(columns, rows); session.focus(true) }
                active = true; ready(true)
                coroutineScope {
                    val writer = launch {
                        for (input in inputs) when (input) {
                            is Input.Bytes -> session.write(input.bytes)
                            is Input.Size -> session.resize(input.cols, input.rows)
                        }
                    }
                    val output = launch { session.output.collect { terminal.appendRemote(it) } }
                    val reason = session.closed.await()
                    output.join(); writer.cancelAndJoin()
                    terminal.markExited(reason.exit_code ?: 0)
                    closed(reason)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e.asHcError()) }
            finally {
                active = false; ready(false)
                withContext(NonCancellable) {
                    withTimeoutOrNull(3000) {
                        attach?.let { session ->
                            try { session.focus(false) } catch (_: Exception) { }
                            try { session.detach() } catch (_: Exception) { }
                        }
                    }
                }
            }
        }
    }
    fun keyCode(code: Int, control: Boolean, alt: Boolean, shift: Boolean) {
        val modifiers = (if (control) com.termux.terminal.KeyHandler.KEYMOD_CTRL else 0) or (if (alt) com.termux.terminal.KeyHandler.KEYMOD_ALT else 0) or (if (shift) com.termux.terminal.KeyHandler.KEYMOD_SHIFT else 0)
        if (active && !observe) view.handleKeyCode(code, modifiers)
    }
    /** Visible screen only: old dialogs in scrollback must not keep cards open. */
    fun screenText(): String = terminal.emulator?.let {
        it.getSelectedText(0, 0, it.mColumns - 1, it.mRows - 1)
    }.orEmpty()
    fun write(text: String) { terminal.write(text) }
    fun dispose() { active = false; ready(false); inputs.close(); job?.cancel() }
}
