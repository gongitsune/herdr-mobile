package io.herdrmobile.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.herdrmobile.android.R
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.android.ui.theme.ProgramIcon
import io.herdrmobile.core.workspace.AgentKind
import io.herdrmobile.core.workspace.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.model.HcJson
import kotlinx.serialization.json.*

internal fun newTabLabels(space: Space, tabId: String): Map<String, String> = mapOf(
    "herdr.space" to (space.machine?.let { space.id.removePrefix("$it/") } ?: space.id),
    "herdr.tab" to tabId, "herdr.seen" to "0",
)

internal fun freshSpaceId(): String = "sp" + UUID.randomUUID().toString().replace("-", "")

internal fun freshTabId(): String = "tb" + UUID.randomUUID().toString().replace("-", "")

// Resolve the shell on the target host and load its login PATH before launching.
// Agent arguments stay separate from shell source, as in herdr-app's remote_shell.
internal fun newTerminalArgv(choice: Int): List<String> = when (choice) {
    1, 2 -> listOf("sh", "-c", """
        shell=${'$'}{SHELL:-/bin/sh}
        case "${'$'}{shell##*/}" in
          fish) exec "${'$'}shell" -lic 'command ${'$'}argv[2..]; exec ${'$'}argv[1] -i' "${'$'}shell" "${'$'}@" ;;
          *) exec "${'$'}shell" -lic '"${'$'}@"; exec "${'$'}0" -i' "${'$'}shell" "${'$'}@" ;;
        esac
    """.trimIndent(), "sh", if (choice == 1) "claude" else "codex")
    else -> listOf("sh", "-c", "exec \"${'$'}{SHELL:-/bin/sh}\" -i")
}

internal fun newTerminalParams(space: Space, choice: Int, command: String): JsonObject = buildJsonObject {
    space.machine?.let { put("on", it) }
    if (space.cwd.isNotBlank()) put("cwd", space.cwd)
    if (choice == 3) put("line", command)
    else put("argv", JsonArray(newTerminalArgv(choice).map(::JsonPrimitive)))
    put("keep", true)
    put("env", buildJsonObject { put("TERM", "xterm-256color"); put("COLORTERM", "truecolor"); put("TERM_PROGRAM", "herdr") })
    put("labels", JsonObject(newTabLabels(space, freshTabId()).mapValues { JsonPrimitive(it.value) }))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTerminalSheet(connections: HostConnections, host: String, space: Space, dismiss: () -> Unit, opened: (String) -> Unit, shellOnly: Boolean = false, newSpace: Boolean = false) {
    var choice by remember { mutableIntStateOf(-1) }
    var folder by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<io.herdrmobile.core.rpc.HcError?>(null) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(confirmValueChange = { !busy })
    fun create(index: Int) {
        if (busy || (index == 3 && command.isBlank())) return
        choice = index
        busy = true
        error = null
        scope.launch {
            try {
                val client = checkNotNull(connections.client(host))
                val cwd = resolveTerminalFolder(client, if (newSpace) folder else space.cwd, space.machine)
                val params = newTerminalParams(space.copy(cwd = cwd), index, command)
                val terminal = HcJson.decodeFromJsonElement<Terminal>(client.call("terminal.run", params))
                opened(terminal.id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.asHcError() }
            finally { busy = false }
        }
    }
    LaunchedEffect(shellOnly) { if (shellOnly) create(0) }
    ModalBottomSheet(sheetState = sheetState, onDismissRequest = { if (!busy) dismiss() }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (newSpace) R.string.new_space else R.string.new_agent_tab), style = MaterialTheme.typography.titleMedium)
            if (newSpace) OutlinedTextField(folder, { folder = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.space_folder)) }, placeholder = { Text(stringResource(R.string.home_folder)) })
            if (shellOnly && busy) CircularProgressIndicator(Modifier.padding(16.dp))
            if (shellOnly && !busy && error != null) TextButton(onClick = { create(0) }) { Text(stringResource(R.string.retry)) }
            listOf(R.string.new_shell, R.string.new_claude, R.string.new_codex, R.string.new_command).forEachIndexed { index, label ->
                if ((!shellOnly && (newSpace || index != 0)) && (!newSpace || index != 3)) Row(
                    Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        if (index == 3) { choice = index; error = null } else create(index)
                    }.padding(vertical = 14.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (index) {
                        1 -> ProgramIcon(AgentKind.CLAUDE_CODE, Modifier.size(24.dp))
                        2 -> ProgramIcon(AgentKind.CODEX, Modifier.size(24.dp))
                        else -> Icon(if (index == 0) Icons.Default.Terminal else Icons.Default.Edit, null)
                    }
                    Text(stringResource(label), Modifier.weight(1f))
                    if (busy && choice == index) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            if (choice == 3) OutlinedTextField(
                command, { command = it }, enabled = !busy, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.new_command_input)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { create(3) }),
                trailingIcon = {
                    IconButton(enabled = !busy && command.isNotBlank(), onClick = { create(3) }) {
                        Icon(Icons.Default.ArrowUpward, stringResource(R.string.send))
                    }
                },
            )
            TextButton(onClick = dismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            error?.let { Text(terminalErrorMessage(it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

internal fun expandHomeFolder(folder: String, home: String): String {
    val path = folder.trim()
    return when {
        path.isEmpty() || path == "~" -> home
        path.startsWith("~/") -> home.trimEnd('/') + path.drop(1)
        else -> path
    }
}

internal suspend fun resolveTerminalFolder(client: io.herdrmobile.core.HcClient, folder: String, machine: String?): String {
    val path = folder.trim()
    if (path.isNotEmpty() && path != "~" && !path.startsWith("~/")) return path
    // Mobile intentionally uses the SSH destination host’s $HOME, rather than the desktop app’s home.
    // An explicit machine routes resolution to that machine instead.
    val result = client.call("machine.exec", buildJsonObject {
        put("argv", JsonArray(listOf("sh", "-c", "printf '%s' \"\$HOME\"").map(::JsonPrimitive)))
        put("timeout", 5)
        machine?.let { put("on", it) }
    }).jsonObject
    if (result["code"]?.jsonPrimitive?.intOrNull != 0 ||
        result["timed_out"]?.jsonPrimitive?.booleanOrNull == true ||
        result["truncated"]?.jsonPrimitive?.booleanOrNull == true) throw io.herdrmobile.core.rpc.HcError("io", "Cannot resolve home")
    val home = java.util.Base64.getDecoder().decode(result["stdout"]!!.jsonPrimitive.content).decodeToString()
    if (!home.startsWith("/")) throw io.herdrmobile.core.rpc.HcError("io", "Invalid home")
    return expandHomeFolder(path, home)
}
