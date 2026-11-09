package io.herdrmobile.core.usage

enum class CommandRun { CHAT, TERMINAL }

data class AgentCommand(
    val name: String,
    val description: String,
    val argumentHint: String = "",
    val trigger: Char = '/',
    val run: CommandRun = CommandRun.CHAT,
    val source: String = "builtin",
)
