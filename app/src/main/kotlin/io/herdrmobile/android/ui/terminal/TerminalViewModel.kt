package io.herdrmobile.android.ui.terminal

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.view.TerminalView
import io.herdrmobile.android.connection.HostConnections
import io.herdrmobile.android.ui.asHcError
import io.herdrmobile.core.*
import io.herdrmobile.core.usage.*
import io.herdrmobile.core.workspace.AgentDetector
import io.herdrmobile.core.workspace.AgentKind
import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.transcript.*
import io.herdrmobile.android.ui.chat.*
import io.herdrmobile.android.ui.chat.visibleChatItems
import io.herdrmobile.android.ui.theme.AtomPalette
import com.termux.terminal.TerminalColors
import io.herdrmobile.core.rpc.HcError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class TerminalViewModel(private val connections: HostConnections, private val hostId: String, private val id: String) : ViewModel() {
    private var presentationClient = connections.client(hostId)
    private val mutablePresentation = MutableStateFlow(
        connections.presentation(hostId, id, presentationClient)?.reopened() ?: DetailPresentation())
    val presentation = mutablePresentation.asStateFlow()
    private fun showPresentation(value: DetailPresentation) {
        mutablePresentation.value = value
        connections.rememberPresentation(hostId, id, presentationClient, value)
    }
    fun selectSurface(value: DetailSurface) { showPresentation(presentation.value.select(value)) }

    val commands = MutableStateFlow<List<AgentCommand>?>(null)
    private data class CommandKey(val client: HcClient, val kind: AgentKind, val machine: String?, val cwd: String)
    private var commandKey: CommandKey? = null
    private var commandWatch: Job? = null
    private var commandRefresh: Job? = null

    private fun currentCommandKey(): CommandKey? {
        if (!connected.value) return null
        val terminal = record.value ?: return null
        val kind = chatAgentKind(terminal) ?: return null
        val client = connections.client(hostId) ?: return null
        val machine = (transcriptState.value as? TranscriptState.Ready)?.binding?.machine
            ?: id.substringBefore('/', "").takeIf { '/' in id }
        val cwd = terminal.foreground?.cwd?.takeIf(String::isNotBlank) ?: terminal.cwd
        return CommandKey(client, kind, machine, cwd)
    }

    fun refreshCommands() {
        val key = currentCommandKey() ?: return
        val store = connections.commands(hostId) ?: return
        if (key != commandKey) {
            commandWatch?.cancel(); commandRefresh?.cancel()
            commandKey = key
            val snapshot = store.commands(key.kind, key.machine, key.cwd)
            commands.value = snapshot.value
            commandWatch = viewModelScope.launch { snapshot.collect { commands.value = it } }
        }
        if (commandRefresh?.isActive == true) return
        commandRefresh = viewModelScope.launch {
            try { store.refresh(key.kind, key.machine, key.cwd) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Store retains the last successful snapshot. */ }
        }
    }

    fun selectCommand(command: AgentCommand) {
        if (!connected.value || !ready.value || observe.value || busy.value || modelConfirmation.value != null) return
        if (currentCommandKey() != commandKey) return
        val query = commandQuery(draft.value.text, chatAgentKind(record.value), activeQuestion(chatItems.value, blocked.value) != null) ?: return
        if (command !in filterCommands(commands.value.orEmpty(), query)) return
        if (command.run == CommandRun.CHAT) {
            draft.value = command.composerValue()
            choiceError.value = null
            return
        }
        val originalDraft = draft.value
        action {
            checkNotNull(connections.client(hostId)).send(id, "${command.trigger}${command.name}")
            if (draft.value == originalDraft) draft.value = TextFieldValue()
            selectSurface(DetailSurface.TERMINAL)
            source?.nudge()
            sentRecently()
        }
    }

    val session = MutableStateFlow<SessionInfo?>(null)
    val account = MutableStateFlow<ClaudeAccount?>(null)
    val pendingModel = MutableStateFlow<AgentModel?>(null)
    private var accountWatch: Job? = null
    private var accountKey: Pair<HcClient, String?>? = null
    private var pendingBaseModel: String? = null
    private var pendingTimeout: Job? = null
    private var confirmationWatch: Job? = null
    val modelConfirmation = MutableStateFlow<String?>(null)
    private fun clearPendingModel() {
        pendingTimeout?.cancel(); pendingTimeout = null; pendingModel.value = null
    }
    private fun watchModelConfirmation() {
        confirmationWatch?.cancel(); modelConfirmation.value = null
        confirmationWatch = viewModelScope.launch {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            var detected = false
            while (isActive) {
                val model = modelSwitchConfirmation(remote?.screenText().orEmpty())
                if (model != null) { detected = true; modelConfirmation.value = model }
                else if (detected) { modelConfirmation.value = null; break }
                if (!detected && android.os.SystemClock.elapsedRealtime() >= deadline) break
                delay(100)
            }
        }
    }
    fun confirmModelSwitch(accept: Boolean) {
        if (modelConfirmation.value == null || !ready.value || observe.value || busy.value) return
        action {
            checkNotNull(connections.client(hostId)).keys(id, listOf(if (accept) "Enter" else "Esc"))
            if (!accept) clearPendingModel()
            source?.nudge()
        }
    }
    fun refreshAccount(force: Boolean = false) {
        if (record.value?.let { AgentDetector.detect(it)?.kind } != AgentKind.CLAUDE_CODE) return
        val machine = (transcriptState.value as? TranscriptState.Ready)?.binding?.machine
            ?: id.substringBefore('/', "").takeIf { '/' in id }
        val client = connections.client(hostId) ?: return
        val store = connections.account(hostId) ?: return
        if (accountKey != (client to machine)) {
            accountKey = client to machine
            accountWatch?.cancel(); account.value = null
            accountWatch = viewModelScope.launch { store.account(machine).collect { account.value = it } }
        }
        viewModelScope.launch {
            try { store.refresh(machine, force) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Store retains the last successful result. */ }
        }
    }
    fun canSwitchModel(): Boolean = connected.value && ready.value && !observe.value && !busy.value &&
        record.value?.status?.state != "working" && activity.value?.running != true && !blocked.value &&
        activeQuestion(chatItems.value, blocked.value) == null && modelConfirmation.value == null
    fun switchModel(model: AgentModel? = null) {
        if (!canSwitchModel()) return
        val kind = record.value?.let { AgentDetector.detect(it)?.kind }
        if (kind !in setOf(AgentKind.CLAUDE_CODE, AgentKind.CODEX)) return
        if (kind == AgentKind.CLAUDE_CODE && model != null && model !in account.value?.models.orEmpty()) return
        action {
            val client = checkNotNull(connections.client(hostId))
            val baseModel = session.value?.model
            client.send(id, if (kind == AgentKind.CLAUDE_CODE && model != null) "/model ${model.value}" else "/model")
            if (kind == AgentKind.CLAUDE_CODE && model != null) {
                clearPendingModel()
                pendingBaseModel = baseModel
                pendingModel.value = model
                pendingTimeout = viewModelScope.launch { delay(120_000); pendingModel.value = null }
                if (pendingModelAcknowledged(baseModel, session.value?.model, pendingModel.value, session.value?.switchedTo)) clearPendingModel()
                watchModelConfirmation()
            } else selectSurface(DetailSurface.TERMINAL)
            source?.nudge()
        }
    }
    val connected = MutableStateFlow(false)
    val record = MutableStateFlow<Terminal?>(null)
    val activity = MutableStateFlow<TurnActivity?>(null)
    val observe = MutableStateFlow(false)
    val chatItems = MutableStateFlow(emptyList<ChatItem>())
    val pendingMessages = MutableStateFlow(emptyList<PendingChatMessage>())
    val transcriptState = MutableStateFlow<TranscriptState?>(null)
    val blocked = MutableStateFlow(false)
    val candidate = MutableStateFlow<TranscriptCandidate?>(null)
    val candidatePreview = MutableStateFlow<List<ChatItem>?>(null)
    val candidateError = MutableStateFlow<HcError?>(null)
    val candidateBusy = MutableStateFlow(false)
    private var source: TranscriptSource? = null
    private var sourceClient: HcClient? = null
    private var chatProcess: Pair<AgentKind, Long?>? = null
    private var sourceWatch: Job? = null
    private var previewJob: Job? = null
    val control = MutableStateFlow(false)
    val alt = MutableStateFlow(false)
    val shift = MutableStateFlow(false)
    val draft = MutableStateFlow(TextFieldValue())
    val error = MutableStateFlow<HcError?>(null)
    val ended = MutableStateFlow(false)
    val recovering = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val stopCoolingDown = MutableStateFlow(false)
    val choiceError = MutableStateFlow<ChoiceError?>(null)
    private var cooldownJob: Job? = null
    private fun sentRecently() {
        stopCoolingDown.value = true
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch { delay(1000); stopCoolingDown.value = false }
    }
    fun consumeModifiers() { control.value = false; alt.value = false; shift.value = false }
    private var generation = 0
    private var remote: RemoteTerminalSession? = null
    private var watch: Job? = null

    fun bind(view: TerminalView) {
        unbind(); error.value = null; ended.value = false
        watch = viewModelScope.launch {
            var previous: Terminal? = null
            var previousClient: HcClient? = null
            var wasConnected = false
            combine(connections.statuses, connections.terminals) { statuses, rows ->
                statuses.find { it.host.id == hostId }?.state to rows.find { it.hostId == hostId && it.terminal.id == id }
            }.collect { (state, row) ->
                val client = connections.client(hostId)
                if (client !== presentationClient) {
                    stopTranscript(); chatProcess = null; pendingMessages.value = emptyList()
                    presentationClient = client
                    mutablePresentation.value = connections.presentation(hostId, id, client)?.reopened() ?: DetailPresentation()
                }
                row?.terminal?.let { showPresentation(presentation.value.withTerminal(it)) }
                connected.value = state is ConnectionState.Connected && row != null && !row.stale
                record.value = row?.terminal
                blocked.value = row?.terminal?.status?.state == "blocked" && !row.stale
                if (state !is ConnectionState.Connected || client == null) {
                    // Let the source retain Ready and its items across a transport interruption.
                    if (client !== sourceClient) stopTranscript()
                    stopStream(); wasConnected = false
                    recovering.value = state is ConnectionState.Reconnecting || state is ConnectionState.Connecting
                    error.value = (state as? ConnectionState.Failed)?.error
                        ?: if (state == null) HcError("not_found", "Host removed") else null
                    return@collect
                }
                val recoveredConnection = !wasConnected || client !== previousClient
                wasConnected = true; previousClient = client
                if (recoveredConnection) { stopStream(); ended.value = false; error.value = null }
                // A reconnect's old snapshot is display-only. Wait for the fresh record.
                if (row == null || row.stale) {
                    if (remote != null) { stopStream(); ended.value = true }
                    recovering.value = !ended.value
                    return@collect
                }
                val terminal = row.terminal
                updateTranscript(client, terminal)
                val recovery = terminal.attachRecovery(previous)
                previous = terminal
                when (recovery) {
                    AttachRecovery.WAIT_FOR_RESTART -> { stopStream(); recovering.value = true; ended.value = false }
                    AttachRecovery.FINISHED -> { stopStream(); recovering.value = false; ended.value = true }
                    AttachRecovery.REATTACH -> { stopStream(); ended.value = false; error.value = null; startStream(view, client) }
                    AttachRecovery.NONE -> if (remote == null && !ended.value && error.value == null) startStream(view, client)
                }
            }
        }
    }
    private fun startStream(view: TerminalView, client: HcClient) {
        val token = ++generation
        recovering.value = true
        remote = RemoteTerminalSession(view, client, id, viewModelScope, observe.value, { control.value }, { alt.value }, { shift.value }, ::consumeModifiers,
            { if (generation == token) { ready.value = it; if (it) recovering.value = false } },
            { failure -> if (generation == token) { error.value = failure; recovering.value = false } },
            { reason -> if (generation == token) {
                recovering.value = reason.reason in setOf("disconnected", "lost", "restarted")
                ended.value = !recovering.value
            } }).also { it.start() }
    }
    private fun stopStream() { confirmationWatch?.cancel(); modelConfirmation.value = null; generation++; remote?.dispose(); remote = null; ready.value = false }
    fun unbind() {
        watch?.cancel(); watch = null; stopStream(); recovering.value = false; connected.value = false
        stopTranscript()
    }
    private fun stopTranscript() {
        commandWatch?.cancel(); commandWatch = null; commandRefresh?.cancel(); commandRefresh = null
        commandKey = null; commands.value = null
        previewJob?.cancel(); sourceWatch?.cancel(); sourceWatch = null
        source?.close(); source = null; sourceClient = null
        activity.value = null
        session.value = null; clearPendingModel()
        confirmationWatch?.cancel(); confirmationWatch = null; modelConfirmation.value = null
        accountWatch?.cancel(); accountWatch = null; accountKey = null; account.value = null
        transcriptState.value = null; chatItems.value = emptyList()
        candidate.value = null; candidatePreview.value = null; candidateError.value = null
        candidateBusy.value = false
    }
    private fun updateTranscript(client: HcClient, terminal: Terminal) {
        val kind = chatAgentKind(terminal)
        val process = kind?.let { it to (terminal.foreground?.pid?.toLong() ?: terminal.pid) }
        if (process != chatProcess) {
            stopTranscript()
            chatProcess = process
            pendingMessages.value = emptyList()
        }
        if (kind != null && sourceClient !== client) followTranscript(client)
        // A new process can have the same kind and cwd, so screen effect keys alone are insufficient.
        if (kind != null && currentCommandKey() != commandKey) refreshCommands()
    }
    private fun followTranscript(client: HcClient) {
        sourceWatch?.cancel(); source?.close()
        sourceClient = client
        val transcript = TranscriptSource(client, id, viewModelScope)
        source = transcript
        sourceWatch = viewModelScope.launch {
            launch { transcript.session.collect {
                session.value = it
                if (pendingModelAcknowledged(pendingBaseModel, it?.model, pendingModel.value, it?.switchedTo)) clearPendingModel()
            } }
            combine(transcript.state, transcript.items, transcript.activity) { state, items, activity -> Triple(state, items, activity) }.collect { (state, items, turnActivity) ->
                showPresentation(presentation.value.withTranscript(state))
                transcriptState.value = state; chatItems.value = visibleChatItems(state, items)
                pendingMessages.value = reconcilePendingMessages(pendingMessages.value, chatItems.value)
                activity.value = turnActivity
            }
        }
    }
    fun findCandidates() { viewModelScope.launch { try { source?.candidates() } catch (e: CancellationException) { throw e } catch (e: Exception) { error.value = e.asHcError() } } }
    fun previewCandidate(value: TranscriptCandidate) {
        previewJob?.cancel(); candidate.value = value; candidatePreview.value = null; candidateError.value = null
        previewJob = viewModelScope.launch {
            candidateBusy.value = true
            try { candidatePreview.value = source?.preview(value) ?: throw HcError("io", "Transcript closed") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { candidateError.value = e.asHcError() }
            finally { candidateBusy.value = false }
        }
    }
    fun dismissCandidate() { previewJob?.cancel(); candidate.value = null; candidatePreview.value = null; candidateError.value = null }
    fun selectCandidate() {
        val value = candidate.value ?: return
        if (candidatePreview.value == null || candidateBusy.value) return
        previewJob = viewModelScope.launch {
            candidateBusy.value = true
            try { source?.select(value) ?: throw HcError("io", "Transcript closed"); candidate.value = null; selectSurface(DetailSurface.CHAT) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { candidateError.value = e.asHcError() }
            finally { candidateBusy.value = false }
        }
    }
    fun choose(question: ChatItem.Question, option: ChatOption) = submitChoice(question, option) {}
    private fun submitChoice(question: ChatItem.Question, option: ChatOption, sent: () -> Unit) {
        if (!canChooseOption(chatItems.value, question, option, blocked.value, ready.value && !observe.value, transcriptState.value is TranscriptState.Ready)) return
        action { try { checkNotNull(connections.client(hostId)).keys(id, option.keys); source?.nudge(); sent() } finally { sentRecently() } }
    }
    fun applyPalette(dark: Boolean) {
        TerminalColors.COLOR_SCHEME.updateWith(AtomPalette.terminalProperties(dark))
        remote?.terminal?.emulator?.mColors?.reset()
        remote?.terminal?.onColorsChanged()
    }
    fun retryConnection() {
        if (connections.statuses.value.find { it.host.id == hostId }?.state !is ConnectionState.Connected) connections.reconnect(hostId)
    }
    fun keyCode(code: Int) { if (ready.value && !observe.value) { remote?.keyCode(code, control.value, alt.value, shift.value); consumeModifiers() } }
    fun key(value: String) { if (ready.value && !observe.value) { remote?.write(value); consumeModifiers() } }
    fun send() {
        if (observe.value || !ready.value || busy.value || draft.value.text.isBlank() || chatAgentKind(record.value) == null) return
        val value = draft.value.text
        val question = activeQuestion(chatItems.value, blocked.value)
        if (question != null) {
            val option = numberedOption(question, value)
            if (option == null) { choiceError.value = ChoiceError.INVALID_NUMBER; return }
            if (option.keys.isEmpty()) { choiceError.value = ChoiceError.MANUAL; return }
            choiceError.value = null
            if (canChooseOption(chatItems.value, question, option, blocked.value, ready.value && !observe.value, transcriptState.value is TranscriptState.Ready)) {
                submitChoice(question, option) { if (draft.value.text == value) draft.value = TextFieldValue() }
            }
            return
        }
        val process = chatProcess
        val pending = pendingChatMessage(value, chatItems.value)
        viewModelScope.launch {
            busy.value = true; error.value = null
            pendingMessages.value = reconcilePendingMessages(pendingMessages.value + pending, chatItems.value)
            var sent = false
            try {
                checkNotNull(connections.client(hostId)).send(id, value)
                sent = true
                if (draft.value.text == value) draft.value = TextFieldValue()
                source?.nudge()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error.value = e.asHcError(); source?.nudge()
            } finally {
                if (!sent || chatProcess != process) pendingMessages.value = pendingMessages.value.filterNot { it.message.id == pending.message.id }
                sentRecently(); busy.value = false
            }
        }
    }
    fun stopWorking() {
        if (!canStopWorking(connected.value && (record.value?.status?.state == "working" || activity.value?.running == true), blocked.value, activeQuestion(chatItems.value, blocked.value), stopCoolingDown.value)) return
        interrupt()
    }
    fun interrupt() { if (!observe.value && ready.value) action { checkNotNull(connections.client(hostId)).interrupt(id) } }
    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true; error.value = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.asHcError() }
            finally { busy.value = false }
        }
    }
    override fun onCleared() { unbind() }
}

enum class ChoiceError { INVALID_NUMBER, MANUAL }
