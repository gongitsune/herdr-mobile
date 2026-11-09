package io.herdrmobile.android.ui.terminal

import android.view.KeyEvent
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.terminalErrorMessage
import io.herdrmobile.android.ui.chat.*

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
    val busy by vm.busy.collectAsStateWithLifecycle()
    val cooldown by vm.stopCoolingDown.collectAsStateWithLifecycle()
    val choiceError by vm.choiceError.collectAsStateWithLifecycle()
    val record by vm.record.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val chatItems by vm.chatItems.collectAsStateWithLifecycle()
    val transcript by vm.transcriptState.collectAsStateWithLifecycle()
    val selected by vm.selectedSurface.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val surface = detailSurface(selected, chatItems)
    val unavailable = transcript is io.herdrmobile.core.transcript.TranscriptState.Unavailable || transcript is io.herdrmobile.core.transcript.TranscriptState.Failed
    val showTerminal = surface == DetailSurface.TERMINAL || unavailable
    val working = connected && (record?.status?.state == "working" || activity?.running == true)
    val live = rememberLiveHeader(working, blocked, activity, chatItems, record?.status_ms, connected)
    val question = activeQuestion(chatItems, blocked)
    val canStop = canStopWorking(working, blocked, question, cooldown)
    val title = record?.title?.ifBlank { record?.name.orEmpty() }?.ifBlank { stringResource(R.string.terminal) } ?: stringResource(R.string.terminal)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
            TopAppBar(title = { if (titleContent != null) titleContent() else Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                io.herdrmobile.android.ui.theme.ProgramAvatar(record?.let { io.herdrmobile.core.workspace.AgentDetector.detect(it)?.kind })
                Spacer(Modifier.width(6.dp))
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            } }, navigationIcon = { if (showMenu) IconButton(onClick = back) { Icon(Icons.Default.Menu, stringResource(R.string.open_menu)) } },
                expandedHeight = 56.dp,
                actions = {
                    SurfaceSwitch(surface) { vm.selectedSurface.value = it }
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
                else {
                    question?.let { QuestionCard(it, ready && !observe && !busy, vm::choose) }
                    TextField(draft, { vm.draft.value = it; vm.choiceError.value = null }, modifier = Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 96.dp), enabled = !observe,
                        placeholder = { Text(if (question != null) stringResource(R.string.choose_number, question.options.size) else stringResource(R.string.message_agent, title)) }, maxLines = 4, shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                        trailingIcon = { FilledIconButton(enabled = ready && !observe && !busy && (draft.isNotBlank() || canStop), onClick = { if (draft.isBlank() && canStop) vm.stopWorking() else vm.send() }, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(if (draft.isBlank() && canStop) Icons.Default.Stop else Icons.Default.ArrowUpward, stringResource(if (draft.isBlank() && canStop) R.string.interrupt else R.string.send))
                        } })
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
            if (surface == DetailSurface.CHAT && unavailable) TranscriptPanel(vm, transcript)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                TerminalViewport(vm, Modifier.fillMaxSize(), visible = showTerminal, retry = retry)
                if (surface == DetailSurface.CHAT && !unavailable) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        TranscriptPanel(vm, transcript)
                        ChatTimeline(chatItems, blocked, Modifier.weight(1f).fillMaxWidth(),
                            running = working || live?.working == true, runningElapsed = live?.elapsed, loading = transcript == null || transcript is io.herdrmobile.core.transcript.TranscriptState.Loading)
                    }
                }
            }
        }
    }
    CandidatePreview(vm)


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
