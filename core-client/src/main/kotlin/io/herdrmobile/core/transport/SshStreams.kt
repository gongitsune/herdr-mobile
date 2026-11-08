package io.herdrmobile.core.transport

import com.jcraft.jsch.*
import io.herdrmobile.core.*
import io.herdrmobile.core.rpc.HcError
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

internal data class CommandResult(val status: Int, val output: String)
internal interface RemoteSsh {
    fun exec(command: String): CommandResult
    fun direct(path: String): ByteTransport
    fun relay(command: String): ByteTransport
}
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

internal fun openCoreStream(remote: RemoteSsh, config: SshConfig): ByteTransport {
    val path = config.socketPath ?: run {
        val lock = "\"\${XDG_STATE_HOME:-\$HOME/.local/state}\"/herdr-core/" + shellQuote(config.coreSession) + "/core.lock"
        val command = "if test -r $lock; then cat $lock; " +
            "elif test -n \"\$XDG_RUNTIME_DIR\"; then printf '%s/herdr-core/%s.sock\\n' \"\$XDG_RUNTIME_DIR\" " +
            shellQuote(config.coreSession) + "; else exit 44; fi"
        val result = remote.exec(command)
        if (result.status != 0) throw HcError(
            if (result.status == 44) "not_running" else if (result.status == 127) "unsupported" else "io",
            "Core lock/runtime path resolution failed (exit " + result.status + ")")
        result.output.trimEnd('\r', '\n')
    }
    validateSocketPath(path)
    if (remote.exec("test -S " + shellQuote(path)).status != 0)
        throw HcError("not_running", "herdr-core is not running")
    try { return remote.direct(path) }
    catch (e: Exception) {
        if (e is java.io.InterruptedIOException || e is InterruptedException) throw e
        // Some servers disallow or do not implement streamlocal. The fallback never launches core.
    }
    if (remote.exec("command -v socat >/dev/null 2>&1").status != 0)
        throw HcError("unsupported", "SSH streamlocal is unavailable and socat is not installed")
    // Socat parses address punctuation after shell parsing, so escape both layers.
    val address = "UNIX-CONNECT:" + path.flatMap {
        if (it in "\\,:'\"") listOf('\\', it) else listOf(it)
    }.joinToString("")
    return remote.relay("exec socat STDIO " + shellQuote(address))
}

internal class JschRemote(private val session: Session) : RemoteSsh {
    override fun exec(command: String): CommandResult {
        val channel = session.openChannel("exec") as ChannelExec
        try {
            // SSH executes via the account's login shell (which may be fish/csh).
            // Explicitly enter POSIX sh before using test/if and portable quoting.
            channel.setCommand("sh -c " + shellQuote(command))
            channel.setInputStream(null)
            channel.setErrStream(BoundedSink())
            val input = channel.inputStream
            channel.connect(10_000)
            val bytes = ByteArrayOutputStream()
            val deadline = System.nanoTime() + 10_000_000_000L
            val buffer = ByteArray(4096)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                while (input.available() > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size, input.available()))
                    if (n < 0) break
                    if (bytes.size() + n > 64 * 1024) throw HcError("io", "SSH command response is too large")
                    bytes.write(buffer, 0, n)
                }
                if (channel.isClosed && input.available() == 0) break
                if (System.nanoTime() > deadline) throw HcError("io", "SSH command timed out")
                Thread.sleep(5)
            }
            return CommandResult(channel.exitStatus, bytes.toString("UTF-8"))
        } finally { channel.disconnect() }
    }

    override fun direct(path: String): ByteTransport {
        val channel = session.openChannel("direct-streamlocal@openssh.com") as ChannelDirectStreamLocal
        try {
            channel.setSocketPath(path)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(10_000)
            return ChannelTransport(channel, input, output)
        } catch (e: Exception) { channel.disconnect(); throw e }
    }
    override fun relay(command: String): ByteTransport {
        val channel = session.openChannel("exec") as ChannelExec
        try {
            // SSH executes via the account's login shell (which may be fish/csh).
            // Explicitly enter POSIX sh before using test/if and portable quoting.
            channel.setCommand("sh -c " + shellQuote(command))
            val errors = BoundedSink()
            channel.setErrStream(errors)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(10_000)
            // Wait for first core bytes after hello rather than sleeping or consuming stream data.
            val checkedInput = object : InputStream() {
                override fun read(): Int = checkRead { input.read() }
                override fun read(bytes: ByteArray, off: Int, len: Int): Int = checkRead { input.read(bytes, off, len) }
                private fun checkRead(read: () -> Int): Int {
                    val n = read()
                    if (n < 0 && channel.exitStatus != 0) {
                        val message = errors.text().lowercase()
                        val kind = if (listOf("no such file", "connection refused", "not a socket").any { it in message })
                            "not_running" else if (channel.exitStatus == 127) "unsupported" else "io"
                        throw HcError(kind, "Unable to connect to core through socat")
                    }
                    return n
                }
            }
            return ChannelTransport(channel, checkedInput, output)
        } catch (e: Exception) { channel.disconnect(); throw e }
    }
}
private class ChannelTransport(
    private val channel: Channel,
    override val input: InputStream,
    override val output: OutputStream,
) : ByteTransport {
    override fun close() { channel.disconnect() }
}
private class BoundedSink : OutputStream() {
    private val bytes = ByteArrayOutputStream()
    @Synchronized override fun write(b: Int) { if (bytes.size() < 8192) bytes.write(b) }
    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) { bytes.write(b, off, minOf(len, 8192 - bytes.size())) }
    @Synchronized fun text(): String = bytes.toString("UTF-8")
}
