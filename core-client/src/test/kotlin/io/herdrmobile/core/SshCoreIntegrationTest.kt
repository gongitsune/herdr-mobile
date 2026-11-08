package io.herdrmobile.core

import io.herdrmobile.core.model.Terminal
import io.herdrmobile.core.rpc.HcError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.*

@EnabledIfEnvironmentVariable(named="HC_IT",matches="1")
class SshCoreIntegrationTest {
    @Test fun localhostRunAttachEventsAndHostKeyPolicy(): Unit = runBlocking {
        IntegrationCore.ensureRunning()
        val generated = SshKeys.generateEd25519("herdr-mobile-test")
        try {
            assertOpenSshCanRead(generated)
            AuthorizedKeyLease(generated.authorizedKey).use {
                withTimeout(120_000) {
                    var fingerprint: String? = null
                    val config = SshConfig("localhost",user=System.getProperty("user.name"),
                        auth=SshAuth.PrivateKey(generated.privatePem),coreSession=IntegrationCore.SESSION)
                    val confirmations = java.util.concurrent.atomic.AtomicInteger()
                    val factory = SshTransportFactory(config) { seen ->
                        withContext(Dispatchers.Default) { confirmations.incrementAndGet(); fingerprint=seen; true }
                    }
                    exerciseClient(factory)
                    // A reconnect using the same initially-unpinned factory retains accepted trust.
                    factory.open().use { }
                    assertEquals(1,confirmations.get())
                    assertNotNull(fingerprint)
                    // Known key succeeds without prompting. Mismatch/decline are rejected before
                    // the deliberately invalid password could be authenticated.
                    val pinned = config.copy(hostKeyFingerprint=checkNotNull(fingerprint))
                    SshTransportFactory(pinned) { error("Known key must not prompt") }.open().use { }
                    val fallbackKey = SshKeys.generateEd25519("herdr-mobile-test")
                    try {
                        // OpenSSH disallows direct-streamlocal for this key, but permits exec.
                        AuthorizedKeyLease("no-port-forwarding " + fallbackKey.authorizedKey).use {
                            exerciseClient(SshTransportFactory(pinned.copy(auth=SshAuth.PrivateKey(fallbackKey.privatePem))) {
                                error("Known key must not prompt")
                            })
                        }
                    } finally { fallbackKey.privatePem.fill(0) }
                    val wrongPin = "SHA256:" + "A".repeat(43)
                    val mismatch = SshTransportFactory(pinned.copy(hostKeyFingerprint=wrongPin,auth=SshAuth.Password("invalid"))) {
                        error("Changed key must not prompt")
                    }
                    assertEquals("host_key",assertFailsWith<HcError> { mismatch.open() }.kind)
                    val declined = SshTransportFactory(config.copy(auth=SshAuth.Password("invalid"))) { false }
                    assertEquals("host_key",assertFailsWith<HcError> { declined.open() }.kind)
                    val badAuth = SshTransportFactory(pinned.copy(auth=SshAuth.PrivateKey(
                        SshKeys.generateEd25519("unused-unregistered-key").privatePem))) { error("Must not prompt") }
                    assertEquals("auth",assertFailsWith<HcError> { badAuth.open() }.kind)
                    val absent = SshTransportFactory(pinned.copy(socketPath="/tmp/herdr-mobile-it-missing-${System.nanoTime()}.sock")) {
                        error("Must not prompt")
                    }
                    assertEquals("not_running",assertFailsWith<HcError> { absent.open() }.kind)
                }
            }
        } finally { generated.privatePem.fill(0) }
    }
    private suspend fun exerciseClient(factory: TransportFactory) = coroutineScope {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client = HcClient(factory,scope)
        var created: Terminal? = null
        try {
            client.connect()
            val store = TerminalStore(client,scope)
            val terminal = client.run(argv=listOf("/bin/cat"),labels=mapOf("herdr-mobile-test" to "1"))
            created=terminal
            assertEquals("1",terminal.labels["herdr-mobile-test"])
            store.terminals.first { list -> list.any { it.id == terminal.id } }
            val session = client.attach(terminal.id,80,24)
            val token = "herdr-mobile-ssh-" + System.nanoTime()
            val output = async {
                val text=StringBuilder()
                session.output.first { text.append(it.decodeToString()); token in text }
                text.toString()
            }
            session.write((token+"\n").toByteArray())
            assertContains(output.await(),token)
            assertContains(client.read(terminal.id),token)
            assertFailsWith<IllegalStateException> { session.output.first() }
            session.detach()
            session.closed.await()
        } finally {
            withContext(NonCancellable) {
                try {
                    created?.let {
                        try { withTimeout(10000) { client.kill(it.id) } }
                        finally { withTimeout(10000) { client.rm(it.id) } }
                    }
                } finally { client.close(); scope.cancel() }
            }
        }
    }
    private fun assertOpenSshCanRead(key: GeneratedKey) {
        val file = Files.createTempFile("herdr-mobile-key-", ".pem",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.write(file,key.privatePem)
            val process = ProcessBuilder("ssh-keygen","-y","-f",file.toString()).redirectErrorStream(true).start()
            check(process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly(); "ssh-keygen timed out" }
            assertEquals(0,process.exitValue())
            val public=process.inputStream.bufferedReader().readText().trim().split(' ').take(2).joinToString(" ")
            assertEquals(key.authorizedKey.split(' ').take(2).joinToString(" "),public)
        } finally { Files.deleteIfExists(file) }
    }
}

/** Append/remove precisely this random key's line, preserving concurrent edits and other keys. */
private class AuthorizedKeyLease(private val line: String) : AutoCloseable {
    private val path: Path = Path.of(System.getProperty("user.home"),".ssh","authorized_keys")
    init {
        check(System.getenv("HC_IT") == "1")
        if (!Files.exists(path.parent))
            Files.createDirectories(path.parent,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        if (!Files.exists(path))
            Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        edit { current ->
            check(!current.contains(line)) { "Fresh generated key unexpectedly already exists" }
            current + (if (current.isEmpty() || current.endsWith("\n")) "" else "\n") + line + "\n"
        }
    }
    override fun close() {
        edit { current ->
            val needle="$line\n"
            val at=current.indexOf(needle)
            check(at >= 0 && (at==0 || current[at-1]=='\n')) { "Test authorized_keys line was changed; refusing broad cleanup" }
            current.removeRange(at,at+needle.length)
        }
    }
    private fun edit(change: (String)->String) {
        FileChannel.open(path,READ,WRITE).use { file ->
            file.lock().use {
                check(file.size() <= 4*1024*1024)
                val input=ByteBuffer.allocate(file.size().toInt())
                while (input.hasRemaining()) check(file.read(input) >= 0)
                // ISO-8859-1 preserves every original byte, including comments in other encodings.
                val updated=change(String(input.array(),Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1)
                file.position(0)
                val output=ByteBuffer.wrap(updated)
                while(output.hasRemaining()) file.write(output)
                file.truncate(updated.size.toLong()); file.force(true)
            }
        }
    }
}
