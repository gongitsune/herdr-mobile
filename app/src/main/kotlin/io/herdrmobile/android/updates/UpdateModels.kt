package io.herdrmobile.android.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

enum class UpdateChannel { STABLE, NIGHTLY }
data class UpdatePreferences(
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val checkOnStartup: Boolean = true,
    val lastChecks: Map<UpdateChannel, Long> = emptyMap(),
)

@Serializable
data class ReleaseAsset(val name: String, val size: Long, @SerialName("browser_download_url") val url: String)
@Serializable
data class AppRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)
data class AvailableUpdate(val release: AppRelease, val asset: ReleaseAsset, val shortSha: String? = null)

// Numbered CI assets contain versionName, workflow run number (versionCode), and short SHA.
private val numberedApk = Regex("^herdr-mobile-.+-([1-9][0-9]*)-([0-9a-fA-F]{7,40})\\.apk$")
fun assetVersionCode(name: String): Long? = numberedApk.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

private data class SemVer(val numbers: List<Long>, val pre: List<String>)
private fun semver(text: String): SemVer? {
    val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(text) ?: return null
    val numbers = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
    val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
    if (pre.any { it.isEmpty() || (it.all(Char::isDigit) && it.length > 1 && it.startsWith('0')) }) return null
    return SemVer(numbers, pre)
}

fun isNewerStableVersion(remote: String, installed: String): Boolean {
    val a = semver(remote) ?: return false
    val b = semver(installed) ?: return false
    a.numbers.zip(b.numbers).forEach { (x, y) -> if (x != y) return x > y }
    if (a.pre.isEmpty() || b.pre.isEmpty()) return a.pre.isEmpty() && b.pre.isNotEmpty()
    a.pre.zip(b.pre).forEach { (x, y) ->
        if (x != y) {
            val xn = x.all(Char::isDigit); val yn = y.all(Char::isDigit)
            return when {
                xn && yn -> if (x.length == y.length) x > y else x.length > y.length
                xn != yn -> !xn
                else -> x > y
            }
        }
    }
    return a.pre.size > b.pre.size
}

fun findUpdate(release: AppRelease, channel: UpdateChannel, versionName: String, versionCode: Long): AvailableUpdate? {
    if (release.draft || (channel == UpdateChannel.STABLE && release.prerelease) ||
        (channel == UpdateChannel.NIGHTLY && release.tag != "nightly")) return null
    val assets = release.assets.filter { it.size > 0 && it.url.startsWith("https://github.com/gongitsune/herdr-mobile/releases/download/") }
    val numbered = assets.filter { assetVersionCode(it.name) != null }.maxByOrNull { assetVersionCode(it.name)!! }
    if (channel == UpdateChannel.NIGHTLY) {
        val asset = numbered?.takeIf { assetVersionCode(it.name)!! > versionCode } ?: return null
        return AvailableUpdate(release, asset, numberedApk.matchEntire(asset.name)!!.groupValues[2].take(7))
    }
    val stable = assets.find { it.name == "herdr-mobile-${release.tag}.apk" }
    if (stable != null) return if (isNewerStableVersion(release.tag, versionName)) AvailableUpdate(release, stable) else null
    return numbered?.takeIf { assetVersionCode(it.name)!! > versionCode }?.let { AvailableUpdate(release, it) }
}

fun shouldCheckOnStartup(preferences: UpdatePreferences, now: Long): Boolean {
    val last = preferences.lastChecks[preferences.channel] ?: return preferences.checkOnStartup
    return preferences.checkOnStartup && (now < last || now - last >= CHECK_INTERVAL_MS)
}

interface UpdateSettings {
    val preferences: kotlinx.coroutines.flow.Flow<UpdatePreferences>
    suspend fun setChannel(channel: UpdateChannel)
    suspend fun setCheckOnStartup(enabled: Boolean)
    suspend fun recordCheck(channel: UpdateChannel, at: Long)
}
interface UpdateNetwork {
    suspend fun release(channel: UpdateChannel): AppRelease
    suspend fun download(asset: ReleaseAsset, progress: (Long) -> Unit): File
}
enum class InstallResult { SUCCESS, CANCELLED, CONFLICT, INVALID, STORAGE, BLOCKED, FAILED }
interface UpdateInstaller {
    val results: kotlinx.coroutines.flow.Flow<InstallResult>
    val pendingPermissionApk: File? get() = null
    fun awaitPermission(apk: File) {}
    fun clearPendingPermission() {}
    fun consumeResult() {}
    fun canInstall(): Boolean
    suspend fun install(apk: File)
}
class UpdateHttpException(val status: Int) : java.io.IOException("HTTP $status")
