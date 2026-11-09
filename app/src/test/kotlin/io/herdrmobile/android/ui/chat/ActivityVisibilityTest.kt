package io.herdrmobile.android.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ActivityVisibilityTest {
    @Test fun workingWaitsEightHundredMillisecondsBeforeShowing() {
        val gate = ActivityVisibility()
        assertFalse(gate.update(0, true, false, false, "tool", false))
        assertFalse(gate.update(799, true, false, false, "tool", false))
        assertTrue(gate.update(800, true, false, false, "tool", false))
    }
    @Test fun anIdleStatusCannotHideUnansweredWorkForUpToTwoSeconds() {
        val gate = ActivityVisibility()
        gate.update(0, true, false, false, "tool", false)
        gate.update(800, true, false, false, "tool", false)
        assertTrue(gate.update(900, false, false, false, "tool", false))
        assertTrue(gate.update(2899, false, false, false, "tool", false))
        assertFalse(gate.update(2900, false, false, false, "tool", false))
    }
    @Test fun additionalUnansweredContentResetsTheTwoSecondWindow() {
        val gate = ActivityVisibility()
        gate.update(0, true, false, false, "one", false); gate.update(800, true, false, false, "one", false)
        gate.update(900, false, false, false, "one", false)
        assertTrue(gate.update(2000, false, false, false, "two", false))
        assertTrue(gate.update(3999, false, false, false, "two", false))
        assertFalse(gate.update(4000, false, false, false, "two", false))
    }
    @Test fun anAnswerSuppressesTrailingWorkingUntilRestThenAnotherEightHundredMilliseconds() {
        val gate = ActivityVisibility()
        gate.update(0, true, false, false, "tool", false); gate.update(800, true, false, false, "tool", false)
        assertFalse(gate.update(900, true, false, true, "answer", false))
        assertFalse(gate.update(3000, true, false, true, "answer", false))
        assertFalse(gate.update(3100, false, false, true, "answer", false))
        assertFalse(gate.update(3200, true, false, true, "answer", false))
        assertFalse(gate.update(3999, true, false, true, "answer", false))
        assertTrue(gate.update(4000, true, false, true, "answer", false))
        assertTrue(gate.update(4100, true, false, true, "answer", false))
    }
    @Test fun blockedIsImmediateAndNewUserInputCanBeginAnotherTurn() {
        val gate = ActivityVisibility()
        gate.update(0, true, false, false, "tool", false); gate.update(800, true, false, false, "tool", false)
        assertFalse(gate.update(900, true, true, false, "question", false))
        assertFalse(gate.update(1000, true, false, false, "new-user", true))
        assertTrue(gate.update(1800, true, false, false, "new-user", false))
    }
}
