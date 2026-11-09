package io.herdrmobile.android.ui.chat

import io.herdrmobile.core.transcript.*
import org.junit.Assert.*
import org.junit.Test

class ChatTurnsTest {
    private fun tool(id: String, verb: String? = "Ran", state: ToolState = ToolState.SUCCEEDED) = ChatItem.ToolCall(id, "exec_command", "./gradlew test", state = state, verb = verb, target = "./gradlew test", atEpochMs = 3000)
    @Test fun contiguousToolsAreGroupedWithoutReorderingNarration() {
        val runs = groupWork(listOf(tool("one"), tool("two"), ChatItem.AssistantText("words", "Checking the result"), tool("three")))
        assertEquals(3, runs.size)
        assertEquals(listOf("one", "two"), (runs[0] as WorkRun.Tools).calls.map { it.id })
        assertTrue(runs[1] is WorkRun.Prose)
        assertEquals("three", (runs[2] as WorkRun.Tools).calls.single().id)
    }
    @Test fun completedWorkFoldsButPromptAndFinalAnswerRemainOutside() {
        val items = listOf(ChatItem.UserMessage("u", "Build", 1000), ChatItem.AssistantText("narration", "Inspecting", 2000), tool("command"), tool("edit", "Edited", ToolState.FAILED), ChatItem.AssistantText("answer", "Ready", 7000), ChatItem.TurnMarker("end", "Turn complete", 8000))
        val turn = (projectTurns(items, false).single() as TimelineEntry.Turn).value
        assertEquals("u", turn.prompt!!.id)
        assertEquals(listOf("answer"), turn.answers.map { it.id })
        assertEquals(7000L, turn.durationMs)
        assertEquals(1, turn.edits); assertEquals(1, turn.commands); assertEquals(1, turn.failures)
        assertFalse(workStartsExpanded(turn))
    }
    @Test fun anIdleStatusAloneDoesNotTurnMidWorkNarrationIntoAFinalAnswer() {
        val rows = listOf(ChatItem.UserMessage("u", "Build"), ChatItem.AssistantText("n", "I will inspect"), tool("still-running", state = ToolState.RUNNING))
        val turn = (projectTurns(rows, false).single() as TimelineEntry.Turn).value
        assertTrue(turn.answers.isEmpty()); assertFalse(turn.recordEnded)
        assertTrue(workStartsExpanded(turn))
    }
    @Test fun finalAnswerBeforeCleanupIsStillVisible() {
        val items = listOf(ChatItem.UserMessage("u", "Build"), ChatItem.AssistantText("final", "Result"), tool("cleanup"), ChatItem.TurnMarker("end", "Turn complete"))
        val turn = (projectTurns(items, false).single() as TimelineEntry.Turn).value
        assertEquals("final", turn.answers.single().id)
        assertFalse(workStartsExpanded(turn))
    }
    @Test fun unfinishedWorkAndUnansweredProseStayReadable() {
        val active = (projectTurns(listOf(ChatItem.UserMessage("u", "Build"), tool("c")), true).single() as TimelineEntry.Turn).value
        assertFalse(active.completed); assertTrue(workStartsExpanded(active))
        val noAnswer = ChatTurn("u", null, listOf(WorkRun.Prose(ChatItem.AssistantText("n", "Important context"))), emptyList(), emptyList(), true, null, 0, 0, 0)
        assertTrue(workStartsExpanded(noAnswer))
    }
    @Test fun onlyCompactionAndMissingHistoryMarkersBecomeVisibleNotices() {
        val items = listOf(ChatItem.TurnMarker("start", "Turn started"), ChatItem.UserMessage("u", "hello"), ChatItem.AssistantText("a", "Hi"), ChatItem.TurnMarker("end", "Turn complete"), ChatItem.TurnMarker("compact", "Context compacted"), ChatItem.TurnMarker("history", "Earlier rollout history is unavailable"))
        val rows = projectTurns(items, false)
        assertEquals(1, rows.filterIsInstance<TimelineEntry.Turn>().size)
        assertEquals(listOf("compact", "history"), rows.filterIsInstance<TimelineEntry.Notice>().map { it.marker.id })
    }
    @Test fun appendingWorkKeepsTheLogicalTurnIdAndDoesNotGuessUnknownToolKinds() {
        val first = listOf(ChatItem.UserMessage("u", "hello"), tool("one", null))
        val a = (projectTurns(first, true).first() as TimelineEntry.Turn).value
        val b = (projectTurns(first + tool("two", "Searched"), true).first() as TimelineEntry.Turn).value
        assertEquals(a.id, b.id); assertEquals(0, b.commands); assertEquals(0, b.edits)
    }
    @Test fun elapsedTimeUsesHostTimestampAndHandlesClockSkew() {
        assertEquals("1:05", elapsedLabel(1000, 66_000))
        assertEquals("0:00", elapsedLabel(2000, 1000))
        assertNull(elapsedLabel(null, 1000))
    }
}
