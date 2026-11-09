package io.herdrmobile.core.workspace

import io.herdrmobile.core.model.Terminal

enum class AgentKind { CLAUDE_CODE, CODEX, PI, OH_MY_PI, OPENCODE, GEMINI, COPILOT, CURSOR, AMP, GROK, OTHER }
data class AgentInfo(val kind: AgentKind, val program: String, val argv: List<String>, val pid: Int? = null)
data class Pane(val id: String, val terminal: Terminal, val agent: AgentInfo? = null,
                val subAgents: List<Pane> = emptyList())
data class Tab(val id: String, val name: String, val panes: List<Pane>, val explicitName: String = "")
data class Space(val id: String, val name: String, val cwd: String, val tabs: List<Tab>,
                 val machine: String? = null, val explicitName: String = "")
