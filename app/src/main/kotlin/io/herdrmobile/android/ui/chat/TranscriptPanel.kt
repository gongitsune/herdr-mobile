package io.herdrmobile.android.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.herdrmobile.android.R
import io.herdrmobile.android.ui.terminal.TerminalViewModel
import io.herdrmobile.android.ui.terminalErrorMessage
import io.herdrmobile.core.transcript.TranscriptState

@Composable
fun TranscriptPanel(vm: TerminalViewModel, state: TranscriptState?) {
    if (vm.demo) Text(stringResource(R.string.workspace_demo), Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
    else Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state) {
            null, TranscriptState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is TranscriptState.Ready -> if (!state.complete) Text(stringResource(R.string.chat_loading), style = MaterialTheme.typography.labelSmall)
            is TranscriptState.Failed -> Text(terminalErrorMessage(state.error), color = MaterialTheme.colorScheme.error)
            is TranscriptState.Unavailable -> {
                Text(stringResource(R.string.transcript_unavailable), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::findCandidates) { Text(stringResource(R.string.find_conversations)) }
                state.candidates.forEach { candidate ->
                    TextButton(onClick = { vm.previewCandidate(candidate) }) { Text("${candidate.sessionId} · ${candidate.path}", maxLines = 2) }
                }
            }
        }
        if (state is TranscriptState.Unavailable || state is TranscriptState.Failed) TextButton(onClick = vm::refreshTranscript) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
fun CandidatePreview(vm: TerminalViewModel) {
    val candidate by vm.candidate.collectAsStateWithLifecycle()
    val items by vm.candidatePreview.collectAsStateWithLifecycle()
    val busy by vm.candidateBusy.collectAsStateWithLifecycle()
    val error by vm.candidateError.collectAsStateWithLifecycle()
    candidate?.let { value -> AlertDialog(onDismissRequest = vm::dismissCandidate, title = { Text(stringResource(R.string.confirm_transcript)) },
        text = { Column(Modifier.heightIn(max = 440.dp)) {
            Text(value.path, style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.confirm_transcript_help), style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(terminalErrorMessage(it), color = MaterialTheme.colorScheme.error) }
            ChatTimeline(items.orEmpty(), false, false, { _, _ -> }, Modifier.weight(1f))
        } },
        confirmButton = { TextButton(enabled = items?.isNotEmpty() == true && !busy && error == null, onClick = vm::selectCandidate) { Text(stringResource(R.string.use_transcript)) } },
        dismissButton = { TextButton(onClick = vm::dismissCandidate) { Text(stringResource(R.string.cancel)) } }) }
}
