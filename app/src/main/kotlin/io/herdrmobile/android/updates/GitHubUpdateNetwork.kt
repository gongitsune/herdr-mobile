package io.herdrmobile.android.updates

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class GitHubUpdateNetwork(
    private val cacheDir: File,
    private val versionName: String,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : UpdateNetwork {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun release(channel: UpdateChannel): AppRelease = request(
        "https://api.github.com/repos/gongitsune/herdr-mobile/releases/" +
            if (channel == UpdateChannel.STABLE) "latest" else "tags/nightly",
        "application/vnd.github+json",
    ) { connection ->
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 2 * 1024 * 1024) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.size > 2 * 1024 * 1024) throw IOException("Release response too large")
        json.decodeFromString<AppRelease>(bytes.toString(Charsets.UTF_8))
    }

    override suspend fun download(asset: ReleaseAsset, progress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        require(asset.size > 0 && asset.url.startsWith("https://github.com/gongitsune/herdr-mobile/releases/download/"))
        val directory = File(cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val partial = File(directory, "update.part")
        val apk = File(directory, "update.apk")
        try {
            request(asset.url, "application/octet-stream") { connection ->
                var received = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > asset.size) throw IOException("APK size mismatch")
                            output.write(buffer, 0, count)
                            progress(received)
                        }
                    }
                }
                if (received != asset.size) throw IOException("APK size mismatch")
            }
            coroutineContext.ensureActive()
            if (!partial.renameTo(apk)) throw IOException("Cannot save APK")
            apk
        } catch (e: Exception) {
            partial.delete(); apk.delete()
            coroutineContext.ensureActive()
            throw e
        }
    }

    private suspend fun <T> request(url: String, accept: String, read: suspend (HttpURLConnection) -> T): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val connection = openConnection(URL(url))
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "herdr-mobile/$versionName")
            // Disconnect a blocking read as soon as the download job is cancelled.
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { connection.disconnect() }
            }
            try {
                if (connection.responseCode !in 200..299) throw UpdateHttpException(connection.responseCode)
                read(connection)
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                throw e
            } finally {
                closer.cancel()
                connection.disconnect()
            }
        }
    }
}
