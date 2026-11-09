package io.herdrmobile.android.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.herdrmobile.core.usage.AgentCommand
import io.herdrmobile.core.workspace.AgentKind

data class CommandQuery(val trigger: Char, val text: String)

/** Only the first token of a supported agent's composer can open completion. */
fun commandQuery(draft: String, kind: AgentKind?, answeringQuestion: Boolean = false): CommandQuery? {
    if (!supportsChat(kind) || answeringQuestion || draft.any(Char::isWhitespace)) return null
    val trigger = draft.firstOrNull() ?: return null
    if (trigger != '/' && !(trigger == '$' && kind == AgentKind.CODEX)) return null
    return CommandQuery(trigger, draft.drop(1))
}

/** Prefer prefix matches, then builtin/custom/skill; preserve source order for ties. */
fun filterCommands(commands: List<AgentCommand>, query: CommandQuery): List<AgentCommand> =
    commands.filter { it.trigger == query.trigger && it.name.contains(query.text, ignoreCase = true) }
        .sortedWith(compareBy<AgentCommand> { if (it.name.startsWith(query.text, ignoreCase = true)) 0 else 1 }
            .thenBy { when (it.source) { "builtin" -> 0; "custom" -> 1; "skill" -> 2; else -> 3 } })

fun AgentCommand.composerValue(): TextFieldValue {
    val text = "$trigger$name" + if (argumentHint.isNotBlank()) " " else ""
    return TextFieldValue(text, selection = TextRange(text.length))
}
