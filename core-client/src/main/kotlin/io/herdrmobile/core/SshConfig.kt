package io.herdrmobile.core

data class SshConfig(
    val host: String,
    val port: Int = 22,
    val user: String,
    val auth: SshAuth,
    val coreSession: String = "default",
    val socketPath: String? = null,
    val hostKeyFingerprint: String? = null,
)

sealed interface SshAuth {
    data class Password(val password: String) : SshAuth {
        override fun toString() = "Password(***REDACTED***)"
    }
    data class PrivateKey(val pem: ByteArray, val passphrase: String? = null) : SshAuth {
        override fun toString() = "PrivateKey(***REDACTED***)"
    }
}

/** Unencrypted OpenSSH private PEM bytes and one authorized_keys line without a newline. */
data class GeneratedKey(val privatePem: ByteArray, val authorizedKey: String) {
    override fun toString() = "GeneratedKey(privatePem=***REDACTED***, authorizedKey=$authorizedKey)"
}
