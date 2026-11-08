package io.herdrmobile.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.herdrmobile.android.R
import io.herdrmobile.core.rpc.HcError

fun Throwable.asHcError(): HcError = this as? HcError ?: HcError("io", "Terminal I/O failed")

@Composable
fun terminalErrorMessage(error: HcError): String = when (error.kind) {
    "auth" -> stringResource(R.string.auth_error)
    "host_key" -> stringResource(R.string.host_key_error)
    "not_running" -> stringResource(R.string.not_running)
    "unsupported" -> stringResource(R.string.unsupported_error)
    "io", "disconnected" -> stringResource(R.string.io_error)
    "not_found", "terminal_not_found" -> stringResource(R.string.terminal_not_found)
    "not_live", "terminal_not_live" -> stringResource(R.string.terminal_not_live)
    "timeout" -> stringResource(R.string.terminal_timeout)
    "input_overflow" -> stringResource(R.string.terminal_input_overflow)
    else -> stringResource(R.string.terminal_error_kind, error.kind)
}
