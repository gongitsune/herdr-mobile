package io.herdrmobile.android.ui.terminal

import io.herdrmobile.core.usage.AgentModel

internal fun modelLabel(id: String): String {
    Regex("claude-([a-z]+)-(\\d+)(?:-(\\d{1,2}))?(?:-.*)?").matchEntire(id)?.let {
        val (family, major, minor) = it.destructured
        return family.replaceFirstChar(Char::uppercaseChar) + " " + major + if (minor.isEmpty()) "" else ".$minor"
    }
    Regex("gpt-(\\d+(?:\\.\\d+)*)(-sol)?").matchEntire(id)?.let {
        return "GPT-${it.groupValues[1]}" + if (it.groupValues[2].isEmpty()) "" else "-Sol"
    }
    return id
}
internal fun currentModel(models: List<AgentModel>, id: String?): AgentModel? =
    models.filter { id != null && it.resolvedModel == id }.minByOrNull { if (it.value == "default") 1 else 0 }
internal fun knownModelLabel(id: String): Boolean = modelLabel(id) != id
internal enum class WindowLabel { SESSION, WEEK, HOURS, UNKNOWN }
internal fun windowLabel(minutes: Int?): WindowLabel = when (minutes) {
    null -> WindowLabel.UNKNOWN
    300 -> WindowLabel.SESSION
    10080 -> WindowLabel.WEEK
    else -> WindowLabel.HOURS
}

/** An initially unknown model cannot acknowledge a pending switch. */
internal fun pendingModelChanged(previous: String?, current: String?): Boolean =
    previous != null && current != null && current != previous

/** Screen text has already been decoded by the terminal emulator. */
internal fun modelSwitchConfirmation(screen: String): String? =
    Regex("Yes,\\s+switch\\s+to\\s+([^\\r\\n]+)").find(screen)
        ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

internal fun pendingModelAcknowledged(base: String?, current: String?, pending: AgentModel?, switchedTo: String?): Boolean =
    pending != null && (pendingModelChanged(base, current) || switchedTo == pending.displayName)

/** A newer selection must never fall back to a stale transcript model. */
internal fun selectedModel(models: List<AgentModel>, id: String?, pending: AgentModel?, switchedTo: String?): AgentModel? =
    when {
        pending != null -> models.firstOrNull { it.value == pending.value }
        switchedTo != null -> models.firstOrNull { it.displayName == switchedTo }
        else -> currentModel(models, id)
    }

internal fun displayedModel(models: List<AgentModel>, id: String?, pending: AgentModel?, switchedTo: String?): String? =
    selectedModel(models, id, pending, switchedTo)?.displayName
        ?: pending?.displayName ?: switchedTo ?: id?.let(::modelLabel)
