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
    var candidates by remember { mutableStateOf(false) }
    if (state is TranscriptState.Unavailable || state is TranscriptState.Failed) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.transcript_unavailable), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { candidates = true; vm.findCandidates() }) { Text(stringResource(R.string.find_conversations)) }
        }
    }
    if (candidates) AlertDialog(onDismissRequest = { candidates = false }, title = { Text(stringResource(R.string.find_conversations)) },
        text = { Column { (state as? TranscriptState.Unavailable)?.candidates.orEmpty().forEach { candidate ->
            TextButton(onClick = { candidates = false; vm.previewCandidate(candidate) }) { Text(candidate.sessionId, maxLines = 1) }
        } } }, confirmButton = { TextButton(onClick = { candidates = false }) { Text(stringResource(R.string.close)) } })

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
            ChatTimeline(items.orEmpty(), false, Modifier.weight(1f))
        } },
        confirmButton = { TextButton(enabled = items?.isNotEmpty() == true && !busy && error == null, onClick = vm::selectCandidate) { Text(stringResource(R.string.use_transcript)) } },
        dismissButton = { TextButton(onClick = vm::dismissCandidate) { Text(stringResource(R.string.cancel)) } }) }
}
