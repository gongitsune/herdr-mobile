package io.herdrmobile.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.herdrmobile.core.SshAuth
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.hostData by preferencesDataStore("hosts")
@Serializable
data class SavedHost(
    val id: String = UUID.randomUUID().toString(),
    val name: String, val host: String, val port: Int = 22,
    val user: String, val session: String = "default",
    val encryptedPassword: String = "",
    val authType: String = "password",
    val encryptedPrivateKey: String = "",
    val authorizedKey: String = "",
    val hostKeyFingerprint: String? = null,
)

class HostsRepository(context: Context) {
    private val store = context.applicationContext.hostData
    private val hostsKey = stringPreferencesKey("hosts_v1")
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(value: String?) = value?.let { json.decodeFromString<List<SavedHost>>(it) }.orEmpty()
    val hosts = store.data.map { decode(it[hostsKey]) }

    suspend fun save(host: SavedHost, password: String? = null, privatePem: ByteArray? = null) = withContext(Dispatchers.IO) {
        val encryptedPassword = password?.let { encrypt(it.toByteArray(Charsets.UTF_8)) }
        val encryptedPrivateKey = privatePem?.let(::encrypt)
        store.edit { preferences ->
            val previous = decode(preferences[hostsKey])
            val current = previous.find { it.id == host.id }
            val sameEndpoint = current?.let { it.host == host.host && it.port == host.port } == true
            val record = host.copy(
                hostKeyFingerprint = if (sameEndpoint) current?.hostKeyFingerprint else null,
                encryptedPassword = if (host.authType == "password") encryptedPassword ?: host.encryptedPassword else "",
                encryptedPrivateKey = if (host.authType == "key") encryptedPrivateKey ?: host.encryptedPrivateKey else "",
                authorizedKey = if (host.authType == "key") host.authorizedKey else "",
            )
            preferences[hostsKey] = json.encodeToString(previous.filterNot { it.id == record.id } + record)
        }
    }
    suspend fun delete(id: String) { store.edit { it[hostsKey] = json.encodeToString(decode(it[hostsKey]).filterNot { h -> h.id == id }) } }
    suspend fun trust(host: SavedHost, fingerprint: String): Boolean {
        var saved = false
        store.edit { preferences ->
            preferences[hostsKey] = json.encodeToString(decode(preferences[hostsKey]).map { current ->
                if (current.id == host.id && current.host == host.host && current.port == host.port &&
                    (current.hostKeyFingerprint == null || current.hostKeyFingerprint == fingerprint)) {
                    saved = true; current.copy(hostKeyFingerprint = fingerprint)
                } else current
            })
        }
        return saved
    }
    suspend fun auth(host: SavedHost): SshAuth = withContext(Dispatchers.IO) {
        when (host.authType) {
            "key" -> SshAuth.PrivateKey(decrypt(host.encryptedPrivateKey))
            "password" -> SshAuth.Password(decrypt(host.encryptedPassword).toString(Charsets.UTF_8))
            else -> error("Unsupported authentication type")
        }
    }
    @Synchronized private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey("herdr.hosts.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("herdr.hosts.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(secret: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(secret), Base64.NO_WRAP)
    }
    private fun decrypt(secret: String): ByteArray {
        val bytes = Base64.decode(secret, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
}
