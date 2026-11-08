package io.herdrmobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object IntegrationCore {
    const val SESSION = "herdr-mobile-it"
    private val home = Path.of(System.getProperty("user.home"))
    private val lock = home.resolve(".local/state/herdr-core/$SESSION/core.lock")

    suspend fun ensureRunning(): String = withContext(Dispatchers.IO) {
        check(System.getenv("HC_IT") == "1") { "Integration tests require HC_IT=1" }
        fun path(): String? = if (Files.exists(lock)) {
            Files.readString(lock).trimEnd('\r', '\n').also(::checkDedicatedPath)
        } else null
        path()?.let { candidate ->
            if (runCatching { UnixTransport(candidate).use { } }.isSuccess) return@withContext candidate
        }
        val runtime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotEmpty() }
            ?: "/run/user/" + Files.getAttribute(home, "unix:uid")
        val socket = "$runtime/herdr-core/$SESSION.sock"
        checkDedicatedPath(socket)
        val output = Files.createTempFile("herdr-mobile-core-start-", ".log")
        try {
            val process = ProcessBuilder("hc", "server", "start").apply {
                environment()["HC_SESSION"] = SESSION
                // Explicit test-only socket also overrides a user's global config or inherited HC_SOCKET.
                environment()["HC_SOCKET"] = socket
                environment()["XDG_STATE_HOME"] = home.resolve(".local/state").toString()
                redirectErrorStream(true)
                redirectOutput(output.toFile())
            }.start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Dedicated test core did not start in time")
            }
            check(process.exitValue() == 0) { "Dedicated core start failed: " + Files.readString(output) }
            val candidate = checkNotNull(path()) { "Dedicated core did not create core.lock" }
            UnixTransport(candidate).use { }
            candidate
        } finally { Files.deleteIfExists(output) }
    }

    private fun checkDedicatedPath(path: String) {
        check(Path.of(path).isAbsolute && Path.of(path).fileName.toString() == "$SESSION.sock") {
            "Refusing socket outside the dedicated integration session"
        }
    }
}

/** Test-only NIO transport. Separate read/write calls avoid Channels streams' shared blocking lock. */
internal class UnixTransport(path: String) : ByteTransport {
    private val socket = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
        try { connect(UnixDomainSocketAddress.of(path)) }
        catch (e: Exception) { close(); throw e }
    }
    override val input = object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b,0,1)<0) -1 else b[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int = socket.read(ByteBuffer.wrap(b,off,len))
    }
    override val output = object : OutputStream() {
        override fun write(b: Int) { write(byteArrayOf(b.toByte())) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            val buffer = ByteBuffer.wrap(b,off,len)
            while (buffer.hasRemaining()) socket.write(buffer)
        }
    }
    override fun close() { socket.close() }
}
