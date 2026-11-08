package io.herdrmobile.core

import io.herdrmobile.core.transcript.*
import io.herdrmobile.core.workspace.AgentKind
import org.junit.jupiter.api.Test
import kotlin.test.*

class TranscriptAdapterTest {
    private fun fixture(name:String)=checkNotNull(javaClass.getResourceAsStream("/transcripts/$name")).bufferedReader().use { it.readLines() }
    @Test fun claudeChoosesCurrentBranchAndSkipsSidechainMetadataAndMalformedLines() {
        val items=projectTranscript(AgentKind.CLAUDE_CODE,fixture("claude-branch.jsonl"),false)
        assertEquals(listOf("Synthetic request","Current branch"),items.filterIsInstance<ChatItem.UserMessage>().map { it.text })
        assertEquals("Synthetic answer",items.filterIsInstance<ChatItem.AssistantText>().single().markdown)
        assertEquals("Synthetic reasoning",items.filterIsInstance<ChatItem.Thinking>().single().text)
        val tool=items.filterIsInstance<ChatItem.ToolCall>().single()
        assertEquals(ToolState.SUCCEEDED,tool.state)
        assertEquals("fixture result",tool.result)
        assertTrue(items.any { it is ChatItem.TurnMarker })
    }
    @Test fun parallelToolSiblingsAreRecoveredAndPairedByIdentity() {
        val items=projectTranscript(AgentKind.CLAUDE_CODE,fixture("claude-parallel.jsonl"),false)
        val tools=items.filterIsInstance<ChatItem.ToolCall>()
        assertEquals(2,tools.size)
        assertEquals("one result",tools.first { it.id=="one" }.result)
        assertEquals(ToolState.FAILED,tools.first { it.id=="two" }.state)
    }
    @Test fun paginatedCodexUsesCompletedItemsAndLatestSnapshotsWithoutResponseDuplicates() {
        val rows=fixture("codex-paginated.jsonl")
        val items=projectTranscript(AgentKind.CODEX,rows,true)
        assertEquals("Synthetic request",items.filterIsInstance<ChatItem.UserMessage>().single().text)
        assertEquals("Final snapshot",items.filterIsInstance<ChatItem.AssistantText>().single().markdown)
        assertEquals(ToolState.SUCCEEDED,items.filterIsInstance<ChatItem.ToolCall>().single().state)
        assertEquals("Synthetic reasoning",items.filterIsInstance<ChatItem.Thinking>().single().text)
        val question=items.filterIsInstance<ChatItem.Question>().single()
        assertEquals("Choose fixture",question.prompt)
        assertTrue(question.options.single().keys.isEmpty()) // No invented approval keystrokes.
        assertTrue(projectTranscript(AgentKind.CODEX,rows,false).none { it is ChatItem.Question })
    }
    @Test fun legacyCodexPairsOutputsAndRollsBackWholeTurns() {
        val items=projectTranscript(AgentKind.CODEX,fixture("codex-legacy.jsonl"),false)
        assertEquals("Legacy request",items.filterIsInstance<ChatItem.UserMessage>().single().text)
        assertEquals("Legacy answer",items.filterIsInstance<ChatItem.AssistantText>().single().markdown)
        assertEquals(ToolState.FAILED,items.filterIsInstance<ChatItem.ToolCall>().single().state)
    }
}
