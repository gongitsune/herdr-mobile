package io.herdrmobile.core

import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSchException
import io.herdrmobile.core.rpc.HcError
import io.herdrmobile.core.transport.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SshTransportTest {
    private val config = SshConfig("localhost",user="test",auth=SshAuth.Password("unused"),coreSession="herdr-mobile-it")
    @Test fun hostKeyConfirmationPinsAndRejectsChangedKeys() {
        val key = "host key".toByteArray()
        var confirmations = 0
        val repo = PinnedHostKeys(null) { fingerprint ->
            confirmations++
            assertEquals(sshFingerprint(key),fingerprint)
            true
        }
        assertEquals(HostKeyRepository.OK,repo.check("host",key))
        assertEquals(HostKeyRepository.OK,repo.check("host",key))
        assertEquals(1,confirmations)
        assertEquals(HostKeyRepository.CHANGED,repo.check("host","changed".toByteArray()))
        assertEquals("host_key",repo.failure!!.kind)
        val known = PinnedHostKeys(sshFingerprint(key)) { error("Must not prompt for a saved key") }
        assertEquals(HostKeyRepository.OK,known.check("host",key))
        assertEquals(HostKeyRepository.CHANGED,known.check("host",byteArrayOf(3)))
        val rejected = PinnedHostKeys(null) { false }
        assertEquals(HostKeyRepository.NOT_INCLUDED,rejected.check("host",key))
        assertEquals("host_key",rejected.failure!!.kind)
        // SSH fingerprints are unpadded SHA-256 over the raw key blob.
        assertEquals(43,sshFingerprint(key).removePrefix("SHA256:").length)
    }
    @Test fun resolvesLockAndUsesDirectStreamlocal() {
        val remote = FakeRemote()
        assertSame(remote.stream,openCoreStream(remote,config))
        assertEquals("/run/user/1000/herdr-core/herdr-mobile-it.sock",remote.directPath)
        assertContains(remote.commands.first(),"core.lock")
        assertContains(remote.commands.first(),"XDG_RUNTIME_DIR")
        assertFalse(remote.commands.any { "hc connect" in it || "server start" in it })
    }
    @Test fun explicitPathIsQuotedAndFallsBackToSocat() {
        val remote = FakeRemote().apply { directFails=true }
        val path = "/tmp/core's socket,option:part.sock"
        openCoreStream(remote,config.copy(socketPath=path))
        assertEquals(path,remote.directPath)
        assertEquals("test -S "+shellQuote(path),remote.commands.first())
        assertTrue(remote.relayCommand!!.startsWith("exec socat STDIO "))
        assertContains(remote.relayCommand!!,"\\,")
        assertContains(remote.relayCommand!!,"\\:")
        assertFalse(remote.commands.any { "core.lock" in it })
    }
    @Test fun missingCoreAndMissingSocatAreClassified() {
        val missing = FakeRemote().apply { socketExists=false }
        assertEquals("not_running",assertFailsWith<HcError> { openCoreStream(missing,config) }.kind)
        assertNull(missing.directPath)
        val noSocat = FakeRemote().apply { directFails=true; socatExists=false }
        assertEquals("unsupported",assertFailsWith<HcError> { openCoreStream(noSocat,config) }.kind)
        assertNull(noSocat.relayCommand)
    }
    @Test fun invalidConfigurationAndAuthenticationFailuresAreClassified() {
        assertEquals("unsupported",assertFailsWith<HcError> { validateSshConfig(config.copy(coreSession="../default")) }.kind)
        assertEquals("host_key",assertFailsWith<HcError> { validateSshConfig(config.copy(hostKeyFingerprint="invalid")) }.kind)
        assertEquals("unsupported",assertFailsWith<HcError> { validateSocketPath("/tmp/foo\nbar") }.kind)
        assertEquals("auth",classifySshError(JSchException("Auth fail for methods 'publickey'")).kind)
        assertEquals("unsupported",classifySshError(JSchException("Algorithm negotiation fail")).kind)
        assertEquals("io",classifySshError(JSchException("Connection refused")).kind)
    }
    @Test fun stateHomeOverridesHomeAndEmptyStateHomeFallsBack() {
        // Execute the actual emitted POSIX resolution command locally, without SSH/core.
        val capture=FakeRemote()
        openCoreStream(capture,config)
        val command=capture.commands.first()
        val directory=Files.createTempDirectory("herdr-mobile-state-home-")
        val home=directory.resolve("home")
        val state=directory.resolve("state with ' quote")
        val homeLock=home.resolve(".local/state/herdr-core/herdr-mobile-it/core.lock")
        val stateLock=state.resolve("herdr-core/herdr-mobile-it/core.lock")
        Files.createDirectories(homeLock.parent)
        Files.createDirectories(stateLock.parent)
        Files.writeString(homeLock,"/tmp/home-choice.sock")
        Files.writeString(stateLock,"/tmp/xdg-choice.sock")
        try {
            for (xdg in listOf(state.toString(),"",null)) {
                val builder=ProcessBuilder("sh","-c",command)
                builder.environment()["HOME"]=home.toString()
                builder.environment().remove("XDG_STATE_HOME")
                xdg?.let { builder.environment()["XDG_STATE_HOME"]=it }
                val process=builder.start()
                try {
                    assertTrue(process.waitFor(5,TimeUnit.SECONDS))
                    assertEquals(0,process.exitValue())
                    assertEquals(if(xdg.isNullOrEmpty()) "/tmp/home-choice.sock" else "/tmp/xdg-choice.sock",
                        process.inputStream.bufferedReader().readText())
                } finally { process.destroyForcibly() }
            }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
    private class FakeRemote : RemoteSsh {
        val stream = object : ByteTransport {
            override val input = ByteArrayInputStream(byteArrayOf())
            override val output = ByteArrayOutputStream()
            override fun close() = Unit
        }
        val commands = mutableListOf<String>()
        var directPath: String? = null
        var relayCommand: String? = null
        var directFails=false
        var socketExists=true
        var socatExists=true
        override fun exec(command: String): CommandResult {
            commands += command
            return when {
                command.startsWith("test -S") -> CommandResult(if(socketExists) 0 else 1,"")
                command.startsWith("command -v") -> CommandResult(if(socatExists) 0 else 127,"")
                else -> CommandResult(0,"/run/user/1000/herdr-core/herdr-mobile-it.sock\n")
            }
        }
        override fun direct(path: String): ByteTransport {
            directPath=path
            if (directFails) throw JSchException("streamlocal unsupported")
            return stream
        }
        override fun relay(command: String): ByteTransport { relayCommand=command; return stream }
    }
}
