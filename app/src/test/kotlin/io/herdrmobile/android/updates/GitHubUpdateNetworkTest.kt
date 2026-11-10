package io.herdrmobile.android.updates

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class GitHubUpdateNetworkTest {
    private val directory = Files.createTempDirectory("herdr-updates-test").toFile()
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun endpointsHeadersAndUnknownFields() = runTest {
        val endpoints = mutableListOf<String>()
        val connections = mutableListOf<FakeConnection>()
        val network = GitHubUpdateNetwork(directory, "0.2.0") { url ->
            endpoints += url.toString()
            FakeConnection("""{"tag_name":"v0.3.0","published_at":"2026-11-10T00:00:00Z","body":"# Changes","ignored":true,"assets":[]}""".toByteArray()).also { connections += it }
        }
        assertEquals("# Changes", network.release(UpdateChannel.STABLE).body)
        network.release(UpdateChannel.NIGHTLY)
        assertTrue(endpoints[0].endsWith("/releases/latest"))
        assertTrue(endpoints[1].endsWith("/releases/tags/nightly"))
        connections.forEach {
            assertEquals("application/vnd.github+json", it.getRequestProperty("Accept"))
            assertEquals("herdr-mobile/0.2.0", it.getRequestProperty("User-Agent"))
            assertTrue(it.instanceFollowRedirects)
            assertTrue(it.disconnected)
        }
    }
    @Test fun rateLimitsAndOversizeMetadataCloseTheConnection() = runTest {
        for (status in listOf(403, 429)) {
            val connection = FakeConnection(byteArrayOf(), status)
            try {
                GitHubUpdateNetwork(directory, "0.1.0") { connection }.release(UpdateChannel.STABLE)
                fail("expected HTTP error")
            } catch (e: UpdateHttpException) { assertEquals(status, e.status) }
            assertTrue(connection.disconnected)
        }
        val connection = FakeConnection(ByteArray(2 * 1024 * 1024 + 1))
        try {
            GitHubUpdateNetwork(directory, "0.1.0") { connection }.release(UpdateChannel.STABLE)
            fail("expected size limit")
        } catch (_: IOException) { }
        assertTrue(connection.disconnected)
    }
    @Test fun downloadReplacesOldApksReportsProgressAndChecksSize() = runTest {
        val updates = File(directory, "updates").apply { mkdirs() }
        File(updates, "old.apk").writeText("old")
        val bytes = ByteArray(140_000) { (it % 256).toByte() }
        val connection = FakeConnection(bytes)
        val progress = mutableListOf<Long>()
        val network = GitHubUpdateNetwork(directory, "0.1.0") { connection }
        val file = network.download(apk("herdr-mobile-v0.2.0.apk", bytes.size.toLong()), progress::add)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), progress.last())
        assertEquals(listOf("update.apk"), updates.list()!!.toList())
        assertEquals("application/octet-stream", connection.getRequestProperty("Accept"))
        assertTrue(connection.disconnected)
    }
    @Test fun truncatedAndOversizeApksAreRemoved() = runTest {
        for (expected in listOf(2L, 4L)) {
            val network = GitHubUpdateNetwork(directory, "0.1.0") { FakeConnection(byteArrayOf(1, 2, 3)) }
            try {
                network.download(apk("herdr-mobile-v0.2.0.apk", expected)) { }
                fail("expected APK size mismatch")
            } catch (_: IOException) { }
            assertTrue(File(directory, "updates").listFiles()!!.isEmpty())
        }
    }
    @Test fun cancellationDisconnectsBlockedReadAndRemovesPartialApk() = runTest {
        val reading = CompletableDeferred<Unit>()
        val disconnected = CountDownLatch(1)
        val connection = object : FakeConnection(byteArrayOf()) {
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    reading.complete(Unit)
                    check(disconnected.await(5, TimeUnit.SECONDS)) { "Cancellation did not disconnect" }
                    throw IOException("disconnected")
                }
            }
            override fun disconnect() { super.disconnect(); disconnected.countDown() }
        }
        val network = GitHubUpdateNetwork(directory, "0.1.0") { connection }
        val job = launch { network.download(apk("herdr-mobile-v0.2.0.apk")) { } }
        reading.await()
        job.cancelAndJoin()
        assertTrue(connection.disconnected)
        assertTrue(File(directory, "updates").listFiles()!!.isEmpty())
    }
    private open class FakeConnection(private val bytes: ByteArray, private val status: Int = 200) : HttpURLConnection(URL("https://example.com")) {
        @Volatile var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    }
}
