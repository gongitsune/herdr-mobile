package io.herdrmobile.android.ui.terminal

import io.herdrmobile.core.usage.*
import org.junit.Assert.*
import org.junit.Test

class SessionPresentationTest {
    @Test fun detectsModelSwitchOnVisibleScreen() {
        val screen = "This conversation is cached for the current model.\n❯ 1. Yes, switch to Sonnet 5.5\n  2. No, go back"
        assertEquals("Sonnet 5.5", modelSwitchConfirmation(screen))
        assertEquals("Opus 5.5", modelSwitchConfirmation("❯ 1. Yes, switch to Opus 5.5  "))
        assertEquals("Sonnet 5.5", modelSwitchConfirmation("Yes, switch\nto Sonnet 5.5"))
        assertNull(modelSwitchConfirmation("No, go back"))
        assertNull(modelSwitchConfirmation("Yes, switch to   "))
        assertNull(modelSwitchConfirmation(""))
    }
    @Test fun pendingSurvivesUsageUpdatesAndClearsOnlyForNewModel() {
        assertFalse(pendingModelChanged("claude-haiku-5-5", "claude-haiku-5-5"))
        assertFalse(pendingModelChanged("claude-haiku-5-5", null))
        assertTrue(pendingModelChanged("claude-haiku-5-5", "claude-sonnet-5-5"))
        assertFalse(pendingModelChanged(null, "claude-sonnet-5-5"))
    }
    @Test fun switchAcknowledgementAndDisplayPriority() {
        val sonnet = AgentModel("sonnet", "claude-sonnet-5-5", "Sonnet 5.5", null)
        val models = listOf(sonnet)
        assertFalse(pendingModelAcknowledged(null, sonnet.resolvedModel, sonnet, null))
        assertTrue(pendingModelAcknowledged(null, null, sonnet, "Sonnet 5.5"))
        assertFalse(pendingModelAcknowledged(null, null, sonnet, "Opus 5.5"))
        assertFalse(pendingModelAcknowledged("old", sonnet.resolvedModel, null, "Sonnet 5.5"))
        assertEquals("Sonnet 5.5", displayedModel(models, "old", sonnet, "Opus 5.5"))
        assertEquals("Opus 5.5", displayedModel(models, sonnet.resolvedModel, null, "Opus 5.5"))
        assertEquals("Sonnet 5.5", displayedModel(models, sonnet.resolvedModel, null, null))
        assertEquals("Sonnet 5.5", displayedModel(models, "old", null, "Sonnet 5.5"))
        assertNull(displayedModel(models, null, null, null))
    }
    @Test fun sheetSelectionFollowsChipPriority() {
        val haiku = AgentModel("haiku", "claude-haiku-5-5", "Haiku 5.5", null)
        val sonnet = AgentModel("sonnet", "claude-sonnet-5-5", "Sonnet 5.5", null)
        val opus = AgentModel("opus", "claude-opus-5-5", "Opus 5.5", null)
        val default = haiku.copy(value = "default", displayName = "Default")
        val models = listOf(default, haiku, sonnet, opus)
        val cases = listOf(
            Triple(opus, sonnet.displayName, opus),
            Triple(null, sonnet.displayName, sonnet),
            Triple(null, null, haiku),
            Triple(default, sonnet.displayName, default),
            Triple(opus.copy(description = "Older metadata"), sonnet.displayName, opus),
        )
        cases.forEach { (pending, switchedTo, expected) ->
            val selected = selectedModel(models, haiku.resolvedModel, pending, switchedTo)
            assertEquals(expected, selected)
            assertEquals(displayedModel(models, haiku.resolvedModel, pending, switchedTo), selected?.displayName)
            assertEquals(listOf(expected), models.filter { it == selected })
        }
        assertNull(selectedModel(models, haiku.resolvedModel, null, "Unknown model"))
        assertEquals("Unknown model", displayedModel(models, haiku.resolvedModel, null, "Unknown model"))
        assertNull(selectedModel(models, "unknown", null, null))
        assertNull(selectedModel(models, null, null, null))
        assertNull(selectedModel(emptyList(), haiku.resolvedModel, opus, sonnet.displayName))
        assertEquals(opus.displayName, displayedModel(emptyList(), haiku.resolvedModel, opus, sonnet.displayName))
    }
    @Test fun modelNamesAndUnknownIds() {
        assertEquals("Opus 5.5", modelLabel("claude-opus-5-5"))
        assertEquals("Sonnet 4", modelLabel("claude-sonnet-4-20250514"))
        assertEquals("GPT-6.1-Sol", modelLabel("gpt-6.1-sol"))
        assertEquals("GPT-5.4", modelLabel("gpt-5.4"))
        assertEquals("custom-model", modelLabel("custom-model"))
        assertFalse(knownModelLabel("custom-model"))
    }
    @Test fun concreteModelPreferredOverDefault() {
        val default = AgentModel("default", "claude-opus-5-5", "Default", null)
        val opus = AgentModel("opus", "claude-opus-5-5", "Opus 5.5", null)
        assertEquals(opus, currentModel(listOf(default, opus), "claude-opus-5-5"))
        assertEquals(opus, currentModel(listOf(opus, default), "claude-opus-5-5"))
        assertNull(currentModel(listOf(default, opus), null))
        assertNull(currentModel(listOf(default, opus), "unknown"))
    }
    @Test fun windowLabels() {
        assertEquals(WindowLabel.SESSION, windowLabel(300))
        assertEquals(WindowLabel.WEEK, windowLabel(10080))
        assertEquals(WindowLabel.HOURS, windowLabel(90))
        assertEquals(WindowLabel.UNKNOWN, windowLabel(null))
    }
    @Test fun sessionUsesActiveFiveHourThenShortestWindow() {
        val week = LimitWindow("weekly", 40.0, 10080, 5000)
        val short = LimitWindow("short", 50.0, 60, 5000)
        val session = LimitWindow("primary", 75.0, 300, 2000)
        val limits = AgentLimits("pro", listOf(week, short, session), 0)
        assertEquals(session, limits.session(1000))
        assertEquals(short, limits.session(2000))
        assertEquals(week, AgentLimits(null, listOf(week), 0).session(1000))
        assertNull(limits.session(5000))
        assertNull(AgentLimits(null, emptyList(), 0).session(1000))
    }
}
