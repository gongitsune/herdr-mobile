package io.herdrmobile.core

import com.jcraft.jsch.*
import io.herdrmobile.core.rpc.HcError
import io.herdrmobile.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.ContinuationInterceptor

/**
 * Opens SSH without starting core. Host verification runs before user authentication.
 * The app must persist an accepted fingerprint INSIDE onUnknownHostKey before returning true,
 * then pass that saved value as SshConfig.hostKeyFingerprint to new factory instances.
 * Accepted trust is pinned in memory for all reconnects using this factory; no repeated TOFU prompt.
 * This factory never persists trust. The suspend callback runs on IO; switch dispatcher for UI.
 */
class SshTransportFactory(
    private val config: SshConfig,
    private val onUnknownHostKey: suspend (fingerprint: String) -> Boolean,
) : TransportFactory {
    private val trust = Mutex()
    @Volatile private var acceptedFingerprint = config.hostKeyFingerprint

    override suspend fun open(): ByteTransport {
        var opened: ByteTransport? = null
        try {
            return withContext(Dispatchers.IO) {
                validateSshConfig(config)
                val parent = currentCoroutineContext()
                val keys = PinnedHostKeys(acceptedFingerprint) { fingerprint ->
                    // JSch invokes this synchronously during key exchange, before user authentication.
                    // Bridge to the suspend UI callback without losing the opening coroutine's cancellation.
                    runBlocking(parent.minusKey(ContinuationInterceptor)) {
                        trust.withLock {
                            // Concurrent opens that started before acceptance must share the same pin.
                            val pinned = acceptedFingerprint
                            if (pinned != null) pinned == fingerprint
                            else onUnknownHostKey(fingerprint).also { accepted ->
                                if (accepted) acceptedFingerprint = fingerprint
                            }
                        }
                    }
                }
                val jsch = JSch()
                jsch.hostKeyRepository = keys
                val session = jsch.getSession(config.user, config.host, config.port)
                try {
                    session.setConfig("StrictHostKeyChecking", "yes")
                    // Pin raw host keys, never allow a certificate path to bypass the fingerprint callback.
                    session.setConfig("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256")
                    // Explicit lightweight BC implementations work on Android 26 without replacing its BC provider.
                    session.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
                    session.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
                    session.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
                    when (val auth = config.auth) {
                        is SshAuth.Password -> {
                            session.setConfig("PreferredAuthentications", "password")
                            session.setPassword(auth.password.toByteArray(Charsets.UTF_8))
                        }
                        is SshAuth.PrivateKey -> {
                            session.setConfig("PreferredAuthentications", "publickey")
                            try {
                                jsch.addIdentity("herdr-mobile", auth.pem.copyOf(), null, auth.passphrase?.toByteArray(Charsets.UTF_8))
                            } catch (e: JSchException) {
                                throw HcError("auth", "Unable to load SSH private key").also { it.initCause(e) }
                            }
                        }
                    }
                    // A finite connect timeout covers DNS/TCP/key exchange/auth; coroutine cancellation interrupts it.
                    runInterruptible { session.connect(15_000) }
                    currentCoroutineContext().ensureActive()
                    val remote = JschRemote(session)
                    val stream = runInterruptible { openCoreStream(remote, config) }
                    try {
                        currentCoroutineContext().ensureActive()
                        OwnedSshTransport(stream, session, jsch).also { opened = it }
                    } catch (e: Exception) { stream.close(); throw e }
                } catch (e: Exception) {
                    session.disconnect()
                    jsch.removeAllIdentity()
                    if (e is CancellationException) throw e
                    throw keys.failure ?: classifySshError(e)
                }
            }
        } catch (e: Exception) {
            opened?.close()
            throw e
        }
    }
}

internal fun validateSshConfig(config: SshConfig) {
    if (config.host.isBlank() || config.user.isBlank() || config.port !in 1..65535 ||
        !Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}").matches(config.coreSession)) {
        throw HcError("unsupported", "Invalid SSH host, port, user or core session")
    }
    config.hostKeyFingerprint?.let {
        if (!Regex("SHA256:[A-Za-z0-9+/]{43}").matches(it))
            throw HcError("host_key", "Invalid saved SHA256 host fingerprint")
    }
    config.socketPath?.let(::validateSocketPath)
}
internal fun validateSocketPath(path: String) {
    if (!path.startsWith("/") || path.any { it == '\u0000' || it == '\r' || it == '\n' })
        throw HcError("unsupported", "Core socket path must be an absolute Unix path")
}

internal fun classifySshError(error: Exception): HcError {
    if (error is HcError) return error
    val message = error.message.orEmpty().lowercase()
    val kind = when {
        error is JSchChangedHostKeyException || error is JSchUnknownHostKeyException -> "host_key"
        message.contains("auth fail") || message.contains("auth cancel") -> "auth"
        message.contains("algorithm negotiation fail") || message.contains("no matching") -> "unsupported"
        else -> "io"
    }
    val description = when (kind) {
        "host_key" -> "SSH host key rejected"
        "auth" -> "SSH authentication failed"
        "unsupported" -> "SSH algorithms are not supported by this host"
        else -> "SSH connection failed"
    }
    return HcError(kind, description).also { it.initCause(error) }
}

internal fun sshFingerprint(key: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

internal class PinnedHostKeys(
    initialFingerprint: String?,
    private val confirm: (String) -> Boolean,
) : HostKeyRepository {
    private var pinned = initialFingerprint
    @Volatile var failure: HcError? = null
        private set
    @Synchronized override fun check(host: String, key: ByteArray): Int {
        val fingerprint = sshFingerprint(key)
        val expected = pinned
        if (expected != null) {
            if (MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), fingerprint.toByteArray(Charsets.US_ASCII)))
                return HostKeyRepository.OK
            failure = HcError("host_key", "SSH host key fingerprint changed")
            return HostKeyRepository.CHANGED
        }
        if (!confirm(fingerprint)) {
            failure = HcError("host_key", "SSH host key was not accepted")
            return HostKeyRepository.NOT_INCLUDED
        }
        pinned = fingerprint
        return HostKeyRepository.OK
    }
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "herdr-mobile fingerprint pin"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

private class OwnedSshTransport(
    private val stream: ByteTransport,
    private val session: Session,
    private val jsch: JSch,
) : ByteTransport {
    override val input get() = stream.input
    override val output get() = stream.output
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { stream.close() }
        finally { session.disconnect(); jsch.removeAllIdentity() }
    }
}
