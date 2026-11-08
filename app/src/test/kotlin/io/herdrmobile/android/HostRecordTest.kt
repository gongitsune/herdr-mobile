package io.herdrmobile.android

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HostRecordTest {
    @Test fun phaseOneRecordsRetainPasswordAndDefaultToPasswordAuthentication() {
        val legacy = """{"id":"existing","name":"Old host","host":"host.local","port":2222,"user":"alice","session":"work","encryptedPassword":"existing-ciphertext"}"""
        val host = Json.decodeFromString<SavedHost>(legacy)
        assertEquals("password", host.authType)
        assertEquals("existing-ciphertext", host.encryptedPassword)
        assertEquals("work", host.session)
        assertNull(host.hostKeyFingerprint)
        assertEquals("", host.encryptedPrivateKey)
    }
    @Test fun keyRecordsRoundTripWithPinAndSession() {
        val host = SavedHost(id = "key-host", name = "Build", host = "build.local", user = "alice", session = "work",
            authType = "key", encryptedPrivateKey = "iv-and-ciphertext", authorizedKey = "ssh-ed25519 public comment",
            hostKeyFingerprint = "SHA256:trusted")
        assertEquals(host, Json.decodeFromString<SavedHost>(Json.encodeToString(host)))
    }
}
