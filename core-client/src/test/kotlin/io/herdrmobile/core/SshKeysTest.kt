package io.herdrmobile.core

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.Base64
import kotlin.test.*

class SshKeysTest {
    @Test fun opensshKeyRoundTripsAndSignsUsingBc() {
        val generated = SshKeys.generateEd25519("herdr-mobile-test")
        assertTrue(generated.privatePem.decodeToString().startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
        assertTrue(generated.authorizedKey.endsWith(" herdr-mobile-test"))
        assertFalse(generated.authorizedKey.contains('\n'))
        val publicBlob = Base64.getDecoder().decode(generated.authorizedKey.split(' ')[1])
        val key = KeyPair.load(JSch(),generated.privatePem,null)
        try {
            assertFalse(key.isEncrypted)
            assertContentEquals(publicBlob,key.publicKeyBlob)
            val identity = JSch()
            identity.addIdentity("generated",generated.privatePem.copyOf(),null,null)
            identity.removeAllIdentity()
            // Exercise the lightweight BC implementation even on a desktop JDK with native EdDSA.
            val data = "herdr-mobile signing test".toByteArray()
            val private = opensshSeed(generated.privatePem)
            val signer = Ed25519Signer()
            signer.init(true,org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(private,0))
            signer.update(data,0,data.size)
            val signature = signer.generateSignature()
            val public = DataInputStream(ByteArrayInputStream(publicBlob)).run { string(); string() }
            signer.init(false,Ed25519PublicKeyParameters(public,0))
            signer.update(data,0,data.size)
            assertTrue(signer.verifySignature(signature))
            private.fill(0)
        } finally { key.dispose(); generated.privatePem.fill(0) }
    }
    @Test fun commentsCannotInjectAuthorizedKeysLines() {
        for (bad in listOf("bad\nssh-rsa attacker","bad\rcomment","bad\u0000comment"))
            assertFailsWith<IllegalArgumentException> { SshKeys.generateEd25519(bad) }
        assertNotEquals(SshKeys.generateEd25519("one").authorizedKey,SshKeys.generateEd25519("two").authorizedKey)
    }
    @Test fun secretsAreNotInToString() {
        assertFalse(SshAuth.Password("secret").toString().contains("secret"))
        assertFalse(SshAuth.PrivateKey(byteArrayOf(1),"secret").toString().contains("secret"))
    }
    @Test fun sshKeygenReadsEveryPaddingAlignment() {
        val executable = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
            .map { Path.of(it,"ssh-keygen") }.firstOrNull { Files.isExecutable(it) }
        assumeTrue(executable != null, "ssh-keygen is not installed")
        // The comment changes the private block length. Include an already-aligned block
        // (length 5), which must have zero padding, as well as all other remainders.
        for (length in 0..7) {
            val key = SshKeys.generateEd25519("x".repeat(length))
            val file = Files.createTempFile("herdr-mobile-key-padding-", ".pem",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            try {
                Files.write(file,key.privatePem)
                val process = ProcessBuilder(executable.toString(),"-y","-f",file.toString())
                    .redirectErrorStream(true).start()
                try {
                    assertTrue(process.waitFor(10,TimeUnit.SECONDS), "ssh-keygen timed out")
                    val output = process.inputStream.bufferedReader().readText().trim()
                    assertEquals(0,process.exitValue(),"ssh-keygen could not read padding alignment $length")
                    assertEquals(key.authorizedKey.split(' ').take(2), output.split(' ').take(2))
                } finally { process.destroyForcibly() }
                val encoded = key.privatePem.decodeToString().lineSequence()
                    .filterNot { it.startsWith("-----") }.joinToString("")
                val input = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
                input.skipNBytes(15)
                repeat(3) { input.string() }
                assertEquals(1,input.readInt())
                input.string()
                val secret=input.string()
                val padding=(8-(131+length)%8)%8
                assertEquals(131+length+padding,secret.size)
                assertContentEquals(ByteArray(padding) { (it+1).toByte() },secret.takeLast(padding).toByteArray())
            } finally { key.privatePem.fill(0); Files.deleteIfExists(file) }
        }
    }
    private fun opensshSeed(pem: ByteArray): ByteArray {
        val encoded = pem.decodeToString().lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
        val input = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
        input.skipNBytes(15)
        assertEquals("none",input.string().decodeToString())
        assertEquals("none",input.string().decodeToString())
        input.string()
        assertEquals(1,input.readInt())
        input.string()
        val secret = DataInputStream(ByteArrayInputStream(input.string()))
        assertEquals(secret.readInt(),secret.readInt())
        assertEquals("ssh-ed25519",secret.string().decodeToString())
        secret.string()
        return secret.string().copyOfRange(0,32)
    }
}
private fun DataInputStream.string(): ByteArray = ByteArray(readInt()).also { readFully(it) }
