package io.herdrmobile.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64

object SshKeys {
    /** Creates an unencrypted openssh-key-v1 key; the app must encrypt it for persistent storage. */
    fun generateEd25519(comment: String): GeneratedKey {
        require(comment.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Key comment must be one line" }
        val random = SecureRandom()
        val seed = Ed25519PrivateKeyParameters(random)
        val public = seed.generatePublicKey().encoded
        val private = seed.encoded
        try {
            val blob = sshBytes { string("ssh-ed25519"); string(public) }
            val check = random.nextInt()
            val secret = sshBytes {
                writeInt(check); writeInt(check)
                string("ssh-ed25519"); string(public); string(private + public); string(comment)
            }
            try {
                // openssh-key-v1 unencrypted private records use an 8-byte block size.
                val padding = (8 - secret.size % 8) % 8
                val encoded = sshBytes {
                    write("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII))
                    string("none"); string("none"); string(byteArrayOf())
                    writeInt(1); string(blob)
                    string(secret + ByteArray(padding) { (it + 1).toByte() })
                }
                try {
                    val base64 = Base64.getEncoder().encodeToString(encoded).chunked(70).joinToString("\n")
                    val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$base64\n-----END OPENSSH PRIVATE KEY-----\n"
                    val authorized = "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob) +
                        if (comment.isEmpty()) "" else " $comment"
                    return GeneratedKey(pem.toByteArray(Charsets.UTF_8), authorized)
                } finally { encoded.fill(0) }
            } finally { secret.fill(0) }
        } finally { private.fill(0) }
    }
}

private fun sshBytes(body: DataOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { it.body() } }.toByteArray()
private fun DataOutputStream.string(value: String) = string(value.toByteArray(Charsets.UTF_8))
private fun DataOutputStream.string(value: ByteArray) { writeInt(value.size); write(value) }
