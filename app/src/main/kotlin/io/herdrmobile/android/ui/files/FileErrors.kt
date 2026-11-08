package io.herdrmobile.android.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.herdrmobile.android.R
import io.herdrmobile.core.files.FileChunk
import io.herdrmobile.core.rpc.HcError

fun requireText(chunk: FileChunk): FileChunk {
    if ('\u0000' in chunk.text) throw HcError("binary_file", "Binary files are not displayed as text")
    return chunk
}

@Composable
fun fileErrorMessage(error: HcError): String = when (error.kind) {
    "binary_file" -> stringResource(R.string.binary_file)
    "not_found" -> stringResource(R.string.file_not_found)
    else -> stringResource(R.string.file_error_kind, error.kind)
}
