package io.herdrmobile.android.ui.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.termux.terminal.TextStyle
import kotlin.math.ceil
import android.view.MotionEvent
import com.termux.view.TerminalView

/** Crop a full-sized observe emulator to its bottom rows; never shrink the remote PTY. */
class TailTerminalView(context: Context) : TerminalView(context, null) {
    private var bottomInset = 0
    private var remoteColumns = 80
    private var remoteRows = 24
    fun remoteSize(columns: Int, rows: Int) {
        val cols = columns.takeIf { it > 0 } ?: 80
        val lines = rows.takeIf { it > 0 } ?: 24
        if (cols != remoteColumns || lines != remoteRows) {
            remoteColumns = cols.coerceIn(4, 512); remoteRows = lines.coerceIn(4, 300); updateSize()
        }
    }
    override fun updateSize() {
        val session = mTermSession ?: return
        val renderer = mRenderer ?: return
        session.updateSize(remoteColumns, remoteRows, renderer.fontWidth.toInt(), renderer.fontLineSpacing)
        mEmulator = session.emulator
        mClient?.onEmulatorSet()
        invalidate()
    }
    override fun setTextSize(textSize: Int) {
        super.setTextSize(textSize)
        val metrics = Paint().apply { typeface = Typeface.MONOSPACE; this.textSize = textSize.toFloat() }
        bottomInset = (ceil(metrics.fontSpacing) + ceil(metrics.ascent()) + ceil(metrics.descent())).toInt()
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val emulator = mEmulator ?: return
        val renderer = mRenderer ?: return
        canvas.drawColor(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
        val offset = (emulator.mRows * renderer.fontLineSpacing + bottomInset - height).coerceAtLeast(0)
        val saved = canvas.save()
        canvas.translate(0f, -offset.toFloat())
        renderer.render(emulator, canvas, 0, -1, -1, -1, -1)
        canvas.restoreToCount(saved)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
