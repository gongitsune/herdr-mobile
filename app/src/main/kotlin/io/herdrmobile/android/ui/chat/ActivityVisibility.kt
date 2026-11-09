package io.herdrmobile.android.ui.chat

/** Monotonic-time hysteresis: settled answers cannot flash a new working header. */
class ActivityVisibility {
    private var visible = false
    private var idleAt: Long? = null
    private var pendingAt: Long? = null
    private var hadAnswer = false
    private var answeredRevision: String? = null
    private var observedRest = false
    private var lastRevision: String? = null

    fun update(nowMs: Long, working: Boolean, blocked: Boolean, answerReadable: Boolean, revision: String?, hasNewPrompt: Boolean): Boolean {
        if (blocked) { visible = false; idleAt = null; pendingAt = null; observedRest = true; return false }
        if (answerReadable && revision != answeredRevision) {
            answeredRevision = revision
            visible = false; hadAnswer = true; observedRest = false; pendingAt = null
        }
        if (!working) {
            observedRest = true; pendingAt = null
            if (visible && !answerReadable && !hasNewPrompt) {
                if (idleAt == null || (revision != lastRevision && revision != null)) idleAt = nowMs
                if (nowMs - idleAt!! >= 2000) visible = false
            } else { visible = false; idleAt = null }
        } else {
            idleAt = null
            if (hadAnswer && !hasNewPrompt && !observedRest) visible = false
            else if (!visible) {
                if (pendingAt == null) pendingAt = nowMs
                if (nowMs - pendingAt!! >= 800) { visible = true; hadAnswer = false; observedRest = false }
            }
        }
        if (hasNewPrompt && !answerReadable) hadAnswer = false
        lastRevision = revision
        return visible
    }
}
