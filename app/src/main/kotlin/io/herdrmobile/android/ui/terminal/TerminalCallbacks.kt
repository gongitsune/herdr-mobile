package io.herdrmobile.android.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.herdrmobile.android.R

internal class TerminalCallbacks(private val view: TerminalView, private val control: () -> Boolean, private val observe: Boolean) : TerminalSessionClient, TerminalViewClient {
    override fun onTextChanged(session: TerminalSession) = view.onScreenUpdated()
    override fun onTitleChanged(session: TerminalSession) = Unit
    override fun onSessionFinished(session: TerminalSession) = view.onScreenUpdated()
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        (view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(view.context.getString(R.string.terminal_clipboard_label), text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        if (observe) return
        val clip = (view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return
        if (clip.itemCount > 0) session.emulator?.paste(clip.getItemAt(0).coerceToText(view.context).toString())
    }
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = view.invalidate()
    override fun onTerminalCursorStateChange(state: Boolean) = view.invalidate()
    override fun getTerminalCursorStyle(): Int = 0
    override fun onScale(scale: Float): Float = 1f
    override fun onSingleTapUp(e: MotionEvent) {
        view.requestFocus()
        if (!observe) (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = observe
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = observe
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = control()
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = observe
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.javaClass.simpleName, e) }
}
