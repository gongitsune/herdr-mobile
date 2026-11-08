package io.herdrmobile.android.ui.chat

import io.herdrmobile.core.transcript.*

object DemoConversation {
    val items: List<ChatItem> = listOf(
        ChatItem.TurnMarker("turn", "1"),
        ChatItem.UserMessage("user", "Build the Android client and check the changes."),
        ChatItem.Thinking("thinking", "Check the workspace, run the build, and review the resulting changes."),
        ChatItem.ToolCall("tool", "Gradle", "./gradlew :app:assembleDebug", "BUILD SUCCESSFUL", ToolState.SUCCEEDED),
        ChatItem.AssistantText("assistant", "## Client ready\n\n- **Agents** grouped by status\n- Spaces with tabs and panes\n- A shared `Composer` for chat and terminal\n\n```kotlin\nval view = TerminalView(context, null)\n```"),
        ChatItem.Question("approval", "Review these changes before continuing.", listOf(ChatOption("Continue", keys = listOf("Enter")), ChatOption("Cancel", keys = listOf("Escape"))), approval = true),
    )
}
