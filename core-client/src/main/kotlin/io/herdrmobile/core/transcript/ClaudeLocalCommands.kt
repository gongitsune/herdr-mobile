package io.herdrmobile.core.transcript

// CSI, OSC and the remaining two-byte ANSI escape sequences.
private val ansi = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[@-_])")
internal fun withoutAnsi(text: String): String = ansi.replace(text, "")
internal fun localCommandTag(text: String, tag: String): String? =
    Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
internal fun switchedModel(text: String): String? = localCommandTag(text, "local-command-stdout")?.let {
    Regex("Set model to `([^`]+)`").find(withoutAnsi(it))?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
}
