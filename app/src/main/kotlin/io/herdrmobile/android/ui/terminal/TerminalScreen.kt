package io.herdrmobile.android.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.terminalErrorMessage
import io.herdrmobile.android.ui.chat.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TerminalScreen(vm: TerminalViewModel, back: () -> Unit) {
    val connected by vm.connected.collectAsStateWithLifecycle()
    val observe by vm.observe.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val recovering by vm.recovering.collectAsStateWithLifecycle()
    val ended by vm.ended.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val record by vm.record.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val receipts by vm.receipts.collectAsStateWithLifecycle()
    val questionJump by vm.questionJump.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var confirmKill by remember { mutableStateOf(false) }
    var peek by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val chatItems by vm.chatItems.collectAsStateWithLifecycle()
    val transcript by vm.transcriptState.collectAsStateWithLifecycle()
    val selected by vm.selectedSurface.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val surface = detailSurface(selected, chatItems)
    val working = connected && (record?.status?.state == "working" || activity?.running == true)
    val live = rememberLiveHeader(working, blocked, activity, chatItems, record?.status_ms, connected)
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().then(if (surface == DetailSurface.CHAT) Modifier.nestedScroll(scroll.nestedScrollConnection) else Modifier),
        topBar = {
            TopAppBar(title = { Column {
                Text(record?.title?.ifBlank { record?.name.orEmpty() }?.ifBlank { stringResource(R.string.terminal) } ?: stringResource(R.string.terminal), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(when { blocked -> R.string.needs_you; working -> R.string.working_ellipsis; observe -> R.string.observe; else -> R.string.terminal }), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                expandedHeight = 56.dp, scrollBehavior = if (surface == DetailSurface.CHAT) scroll else null,
                actions = {
                    SurfaceSwitch(surface) { vm.selectedSurface.value = it }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.terminal_menu)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.live_terminal)) }, onClick = { menu = false; peek = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.interrupt)) }, enabled = ready && !observe && !busy, onClick = { menu = false; vm.interrupt() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.kill)) }, enabled = ready && !observe && !busy, onClick = { menu = false; confirmKill = true })
                            DropdownMenuItem(text = { Text(stringResource(if (observe) R.string.interactive else R.string.observe)) }, onClick = { menu = false; vm.observe.value = !observe })
                            DropdownMenuItem(text = { Text(stringResource(R.string.read_text)) }, enabled = !busy, onClick = { menu = false; vm.read() })
                        }
                    }
                })
        },
        bottomBar = {
            Column(Modifier.imePadding().navigationBarsPadding()) {
                if (surface == DetailSurface.TERMINAL) TerminalKeys(vm, ready && !observe)
                TextField(draft, { vm.draft.value = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), enabled = !observe && !busy,
                    placeholder = { Text(stringResource(R.string.composer)) }, maxLines = 4, shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                    trailingIcon = { FilledIconButton(enabled = ready && !observe && !busy && draft.isNotBlank(), onClick = vm::send, modifier = Modifier.padding(end = 4.dp)) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send)) } })
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
            Box(Modifier.fillMaxWidth().weight(1f)) {
                TerminalViewport(vm, Modifier.fillMaxSize(), visible = surface == DetailSurface.TERMINAL, retry = retry)
                if (surface == DetailSurface.CHAT) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        TranscriptPanel(vm, transcript)
                        ChatTimeline(chatItems, blocked, ready && !observe && !busy, vm::choose, Modifier.weight(1f).fillMaxWidth(),
                            running = working || live?.working == true, runningElapsed = live?.elapsed, receipts = receipts, questionJump = questionJump,
                            answerQuestion = { if (chatItems.any { it is io.herdrmobile.core.transcript.ChatItem.Question }) vm.jumpToQuestion() else peek = true },
                            liveContent = live?.let { header -> { LiveActivity(vm, header) { peek = true } } })
                    }
                }
            }
        }
    }
    CandidatePreview(vm)
    if (peek) TerminalPeek(vm, { peek = false }) { vm.selectedSurface.value = DetailSurface.TERMINAL }
    if (confirmKill) AlertDialog(onDismissRequest = { confirmKill = false }, title = { Text(stringResource(R.string.kill)) },
        text = { Text(stringResource(R.string.kill_confirm)) }, confirmButton = { TextButton(onClick = { confirmKill = false; vm.kill() }) { Text(stringResource(R.string.kill)) } }, dismissButton = { TextButton(onClick = { confirmKill = false }) { Text(stringResource(R.string.cancel)) } })
    text?.let { value -> AlertDialog(onDismissRequest = { vm.text.value = null }, title = { Text(stringResource(R.string.read_text)) },
        text = { SelectionContainer { Text(value, Modifier.verticalScroll(rememberScrollState())) } }, confirmButton = { TextButton(onClick = { vm.text.value = null }) { Text(stringResource(R.string.close)) } }) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SurfaceSwitch(surface: DetailSurface, select: (DetailSurface) -> Unit) {
    val labels = listOf(stringResource(R.string.terminal), stringResource(R.string.chat))
    ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }, modifier = Modifier.width(96.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        DetailSurface.entries.forEachIndexed { index, value -> customItem(buttonGroupContent = {
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
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        HorizontalFloatingToolbar(expanded = true, modifier = Modifier.height(48.dp), contentPadding = PaddingValues(0.dp)) {
            TextButton(enabled = enabled, onClick = { vm.keyCode(KeyEvent.KEYCODE_ESCAPE) }, contentPadding = PaddingValues(6.dp)) { Text(stringResource(R.string.esc)) }
            TextButton(enabled = enabled, onClick = { vm.keyCode(KeyEvent.KEYCODE_TAB) }, contentPadding = PaddingValues(6.dp)) { Text(stringResource(R.string.terminal_tab)) }
            ToggleButton(checked = control, enabled = enabled, onCheckedChange = { vm.control.value = it }, modifier = Modifier.height(40.dp), contentPadding = PaddingValues(6.dp)) { Text(stringResource(R.string.ctrl)) }
            listOf(Triple(KeyEvent.KEYCODE_DPAD_UP, Icons.Default.ArrowUpward, R.string.up), Triple(KeyEvent.KEYCODE_DPAD_DOWN, Icons.Default.ArrowDownward, R.string.down), Triple(KeyEvent.KEYCODE_DPAD_LEFT, Icons.AutoMirrored.Filled.ArrowBack, R.string.left), Triple(KeyEvent.KEYCODE_DPAD_RIGHT, Icons.AutoMirrored.Filled.ArrowForward, R.string.right)).forEach { (code, icon, label) ->
                IconButton(enabled = enabled, onClick = { vm.keyCode(code) }) { Icon(icon, stringResource(label), Modifier.size(18.dp)) }
            }
            TextButton(enabled = enabled, onClick = { vm.key("\u0003") }, contentPadding = PaddingValues(6.dp)) { Text(stringResource(R.string.ctrl_c)) }
            TextButton(enabled = enabled, onClick = { vm.keyCode(KeyEvent.KEYCODE_ENTER) }, contentPadding = PaddingValues(6.dp)) { Text(stringResource(R.string.enter)) }
        }
    }
}
