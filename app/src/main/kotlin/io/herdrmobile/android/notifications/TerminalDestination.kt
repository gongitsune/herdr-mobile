package io.herdrmobile.android.notifications

import android.net.Uri

data class TerminalDestination(val hostId: String, val terminalId: String) {
    fun uri(): Uri = Uri.Builder().scheme("herdr").authority("terminal").appendPath(hostId).appendPath(terminalId).build()
    companion object {
        fun from(uri: Uri?): TerminalDestination? {
            if (uri?.scheme != "herdr" || uri.authority != "terminal" || uri.pathSegments.size != 2) return null
            return TerminalDestination(uri.pathSegments[0], uri.pathSegments[1])
        }
    }
}
