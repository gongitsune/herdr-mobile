package io.herdrmobile.android.ui.terminal

import android.view.KeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.terminalErrorMessage
import io.herdrmobile.android.ui.chat.*
import io.herdrmobile.core.transcript.TranscriptState
import io.herdrmobile.core.workspace.AgentKind

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TerminalScreen(vm: TerminalViewModel, titleContent: (@Composable () -> Unit)? = null, workspaceFiles: (() -> Unit)? = null, closeTab: (() -> Unit)? = null, renameTab: (() -> Unit)? = null, canRename: Boolean = false, relatedAgents: @Composable () -> Unit = {}, showMenu: Boolean = true, back: () -> Unit) {
    val connected by vm.connected.collectAsStateWithLifecycle()
    val observe by vm.observe.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val recovering by vm.recovering.collectAsStateWithLifecycle()
    val ended by vm.ended.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val modelConfirmation by vm.modelConfirmation.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val cooldown by vm.stopCoolingDown.collectAsStateWithLifecycle()
    val choiceError by vm.choiceError.collectAsStateWithLifecycle()
    val record by vm.record.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val chatItems by vm.chatItems.collectAsStateWithLifecycle()
    val pendingMessages by vm.pendingMessages.collectAsStateWithLifecycle()
    val transcript by vm.transcriptState.collectAsStateWithLifecycle()
    val presentation by vm.presentation.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    LaunchedEffect(vm, connected, record?.foreground, (transcript as? io.herdrmobile.core.transcript.TranscriptState.Ready)?.binding?.machine) {
        while (true) { if (connected) vm.refreshAccount(); kotlinx.coroutines.delay(300_000) }
    }
    val kind = presentation.kind
    val surface = presentation.surface
    val showTerminal = surface == DetailSurface.TERMINAL
    val terminalAlpha by animateFloatAsState(if (showTerminal) 1f else 0f, tween(150), label = "terminalSurface")
    val working = connected && (record?.status?.state == "working" || activity?.running == true)
    val live = rememberLiveHeader(working, blocked, activity, chatItems, record?.status_ms, connected)
    val question = activeQuestion(chatItems, blocked)
    val commands by vm.commands.collectAsStateWithLifecycle()
    val commandQuery = commandQuery(draft.text, kind, question != null || modelConfirmation != null)
    val cwd = record?.foreground?.cwd?.takeIf(String::isNotBlank) ?: record?.cwd
    val machine = (transcript as? TranscriptState.Ready)?.binding?.machine
    LaunchedEffect(vm, connected, kind, cwd, machine) {
        if (connected) vm.refreshCommands()
    }
    LaunchedEffect(vm, connected, commandQuery?.trigger) {
        if (connected && commandQuery != null) vm.refreshCommands()
    }
    val canStop = canStopWorking(working, blocked, question, cooldown)
    val title = record?.title?.ifBlank { record?.name.orEmpty() }?.ifBlank { stringResource(R.string.terminal) } ?: stringResource(R.string.terminal)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
            TopAppBar(title = { if (titleContent != null) titleContent() else Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                io.herdrmobile.android.ui.theme.ProgramAvatar(kind)
                Spacer(Modifier.width(6.dp))
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            } }, navigationIcon = { if (showMenu) IconButton(onClick = back) { Icon(Icons.Default.Menu, stringResource(R.string.open_menu)) } },
                expandedHeight = 56.dp,
                actions = {
                    if (supportsChat(kind) && surface != null) SurfaceSwitch(surface, vm::selectSurface)
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, stringResource(R.string.terminal_menu)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.interrupt)) }, enabled = ready && !observe && !busy, onClick = { menu = false; vm.interrupt() })
                            DropdownMenuItem(text = { Text(stringResource(if (observe) R.string.interactive else R.string.observe)) }, onClick = { menu = false; vm.observe.value = !observe })
                            workspaceFiles?.let { DropdownMenuItem(text = { Text(stringResource(R.string.workspace_panel)) }, onClick = { menu = false; it() }) }
                            renameTab?.let { DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, enabled = canRename, onClick = { menu = false; it() }) }
                            closeTab?.let { DropdownMenuItem(text = { Text(stringResource(R.string.close_tab)) }, onClick = { menu = false; it() }) }
                        }
                    }
                })
                relatedAgents()
            }
        },
        bottomBar = {
            Column(Modifier.imePadding().navigationBarsPadding()) {
                if (showTerminal) TerminalKeys(vm, ready && !observe)
                else if (surface == DetailSurface.CHAT) {
                    if (modelConfirmation != null) ModelSwitchCard(modelConfirmation!!, ready && !observe && !busy, vm::confirmModelSwitch)
                    else question?.let { QuestionCard(it, ready && !observe && !busy, vm::choose) }
                    TextField(draft, { vm.draft.value = it; vm.choiceError.value = null }, modifier = Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 96.dp), enabled = !observe,
                        placeholder = { Text(if (question != null) stringResource(R.string.choose_number, question.options.size) else stringResource(R.string.message_agent, title)) }, maxLines = 4, shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                        trailingIcon = { FilledIconButton(enabled = ready && !observe && !busy && (draft.text.isNotBlank() || canStop), onClick = { if (draft.text.isBlank() && canStop) vm.stopWorking() else vm.send() }, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(if (draft.text.isBlank() && canStop) Icons.Default.Stop else Icons.Default.ArrowUpward, stringResource(if (draft.text.isBlank() && canStop) R.string.interrupt else R.string.send))
                        } })
                    SessionControls(vm)
                    if (choiceError != null && question != null) Text(if (choiceError == ChoiceError.MANUAL) stringResource(R.string.choice_manual) else stringResource(R.string.invalid_choice, question.options.size), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (error != null || ended || recovering) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(error?.let { terminalErrorMessage(it) } ?: stringResource(if (recovering) R.string.terminal_recovering else R.string.attach_closed), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { vm.retryConnection(); retry++ }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(stringResource(R.string.retry), style = MaterialTheme.typography.labelSmall) }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                // Keep one AndroidView and attach alive while the visible surfaces crossfade.
                TerminalViewport(vm, Modifier.fillMaxSize().graphicsLayer { alpha = terminalAlpha },
                    visible = showTerminal || terminalAlpha > 0f, interactive = showTerminal, retry = retry)
                Crossfade(surface, Modifier.fillMaxSize(), animationSpec = tween(150), label = "detailSurface") { displayed ->
                    when (displayed) {
                        null -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                        DetailSurface.TERMINAL -> Unit
                        DetailSurface.CHAT -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column {
                                TranscriptPanel(vm, transcript)
                                if (transcript is TranscriptState.NotStarted && chatItems.isEmpty() && pendingMessages.isEmpty()) {
                                    EmptyConversation(kind, record?.foreground?.cwd?.takeIf { it.isNotBlank() } ?: record?.cwd.orEmpty(), Modifier.weight(1f))
                                } else ChatTimeline(chatItems, blocked, Modifier.weight(1f).fillMaxWidth(),
                                    running = working || live?.working == true, runningElapsed = live?.elapsed, loading = transcript == null || transcript is TranscriptState.Loading,
                                    pendingMessages = pendingMessages.map { it.message })
                            }
                        }
                    }
                }
                // Scaffold padding excludes the top bar, composer and IME from these constraints.
                if (surface == DetailSurface.CHAT) CommandSuggestionsPanel(
                    commandQuery, commands, ready && !observe && !busy, maxHeight = maxHeight * .4f,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter), select = vm::selectCommand)
            }
        }
    }
    CandidatePreview(vm)


}

@Composable
private fun EmptyConversation(kind: AgentKind?, cwd: String, modifier: Modifier = Modifier) {
    val name = stringResource(if (kind == AgentKind.CLAUDE_CODE) R.string.new_claude else R.string.new_codex)
    val folder = cwd.trimEnd('/').substringAfterLast('/').ifBlank { cwd }
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Bottom, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(stringResource(R.string.chat_not_started, name), style = MaterialTheme.typography.bodyLarge)
        if (folder.isNotBlank()) Text(folder, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SurfaceSwitch(surface: DetailSurface, select: (DetailSurface) -> Unit) {
    val labels = listOf(stringResource(R.string.chat), stringResource(R.string.terminal))
    ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }, modifier = Modifier.width(96.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(DetailSurface.CHAT, DetailSurface.TERMINAL).forEachIndexed { index, value -> customItem(buttonGroupContent = {
            ToggleButton(checked = surface == value, onCheckedChange = { select(value) }, modifier = Modifier.width(44.dp).height(40.dp), contentPadding = PaddingValues(0.dp),
                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes()) {
                Icon(if (value == DetailSurface.TERMINAL) Icons.Default.Terminal else Icons.Default.ChatBubbleOutline, labels[index], Modifier.size(20.dp))
            }
        }, menuContent = { state -> DropdownMenuItem(text = { Text(labels[index]) }, onClick = { select(value); state.dismiss() }) }) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TerminalKeys(vm: TerminalViewModel, enabled: Boolean) {
    val control by vm.control.collectAsStateWithLifecycle()
    val alt by vm.alt.collectAsStateWithLifecycle()
    val shift by vm.shift.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            listOf(R.string.esc to KeyEvent.KEYCODE_ESCAPE, R.string.terminal_tab to KeyEvent.KEYCODE_TAB).forEach { (label, code) ->
                TextButton(enabled = enabled, onClick = { vm.keyCode(code) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) }
            }
            TextButton(enabled = enabled, onClick = { vm.key("\u0003") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.ctrl_c), style = MaterialTheme.typography.labelSmall) }
            listOf(Triple(R.string.key_ctrl, control, vm.control), Triple(R.string.key_alt, alt, vm.alt), Triple(R.string.key_shift, shift, vm.shift)).forEach { (label, checked, state) ->
                TextButton(enabled = enabled, onClick = { state.value = !checked }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)) { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) }
            }
            TextButton(enabled = enabled, onClick = { vm.keyCode(KeyEvent.KEYCODE_ENTER) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.enter), style = MaterialTheme.typography.labelSmall) }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) { Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, stringResource(if (expanded) R.string.collapse else R.string.expand)) }
        }
        if (expanded) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(Triple(KeyEvent.KEYCODE_DPAD_UP, Icons.Default.ArrowUpward, R.string.up), Triple(KeyEvent.KEYCODE_DPAD_DOWN, Icons.Default.ArrowDownward, R.string.down), Triple(KeyEvent.KEYCODE_DPAD_LEFT, Icons.AutoMirrored.Filled.ArrowBack, R.string.left), Triple(KeyEvent.KEYCODE_DPAD_RIGHT, Icons.AutoMirrored.Filled.ArrowForward, R.string.right)).forEach { (code, icon, label) ->
                IconButton(enabled = enabled, onClick = { vm.keyCode(code) }) { Icon(icon, stringResource(label), Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun ModelSwitchCard(model: String, enabled: Boolean, choose: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.model_switch_confirm, model))
            Text(stringResource(R.string.model_switch_cost), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(enabled = enabled, onClick = { choose(true) }) { Text(stringResource(R.string.model_switch_accept)) }
                TextButton(enabled = enabled, onClick = { choose(false) }) { Text(stringResource(R.string.model_switch_cancel)) }
            }
        }
    }
}
