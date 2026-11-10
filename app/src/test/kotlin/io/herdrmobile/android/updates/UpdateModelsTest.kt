package io.herdrmobile.android.updates

import org.junit.Assert.*
import org.junit.Test

internal fun apk(name: String, size: Long = 123) = ReleaseAsset(name, size,
    "https://github.com/gongitsune/herdr-mobile/releases/download/nightly/$name")
internal fun stableRelease(tag: String = "v0.2.0") = AppRelease(tag, assets = listOf(apk("herdr-mobile-$tag.apk")))
// Example commit count, shared by local and CI builds of the same revision.
internal fun nightlyRelease(code: Long = 19) = AppRelease("nightly", prerelease = true,
    assets = listOf(apk("herdr-mobile-0.1.0-$code-abcdef1.apk")))

class UpdateModelsTest {
    @Test fun nightlyUsesVersionCodeAndShowsSha() {
        val release = nightlyRelease()
        assertEquals(19L, assetVersionCode(release.assets.single().name))
        assertEquals("abcdef1", findUpdate(release, UpdateChannel.NIGHTLY, "9.0.0", 18)?.shortSha)
        assertNull(findUpdate(release, UpdateChannel.NIGHTLY, "0.0.1", 19))
        assertNull(findUpdate(release, UpdateChannel.NIGHTLY, "0.0.1", 20))
        assertNull(assetVersionCode("herdr-mobile-v0.2.0.apk"))
        assertNull(assetVersionCode("herdr-mobile-0.2.0-99999999999999999999999-abcdef1.apk"))
        assertNull(assetVersionCode("other-0.2.0-19-abcdef1.apk"))
    }
    @Test fun stableUsesSemanticVersionAndIgnoresSameOrOlder() {
        assertNotNull(findUpdate(stableRelease("v0.10.0"), UpdateChannel.STABLE, "0.9.0", 999))
        assertNull(findUpdate(stableRelease(), UpdateChannel.STABLE, "0.2.0", 1))
        assertNull(findUpdate(stableRelease(), UpdateChannel.STABLE, "0.3.0", 1))
        assertTrue(isNewerStableVersion("v1.0.0", "1.0.0-rc.1"))
        assertTrue(isNewerStableVersion("1.0.0-rc.10", "1.0.0-rc.9"))
        assertTrue(isNewerStableVersion("1.0.0-beta", "1.0.0-9"))
        assertFalse(isNewerStableVersion("1.0.0-alpha", "1.0.0"))
        assertFalse(isNewerStableVersion("1.0.0+build.9", "1.0.0+build.1"))
        assertFalse(isNewerStableVersion("1.0.0-01", "1.0.0-0"))
        assertFalse(isNewerStableVersion("nightly", "0.1.0"))
    }
    @Test fun excludesDraftsPrereleasesWrongTagsAndUnsafeAssets() {
        assertNull(findUpdate(stableRelease().copy(draft = true), UpdateChannel.STABLE, "0.1.0", 1))
        assertNull(findUpdate(stableRelease().copy(prerelease = true), UpdateChannel.STABLE, "0.1.0", 1))
        assertNull(findUpdate(stableRelease(), UpdateChannel.NIGHTLY, "0.1.0", 1))
        val empty = stableRelease().copy(assets = listOf(apk("herdr-mobile-v0.2.0.apk", size = 0)))
        assertNull(findUpdate(empty, UpdateChannel.STABLE, "0.1.0", 1))
        val foreign = stableRelease().copy(assets = listOf(apk("herdr-mobile-v0.2.0.apk").copy(url = "https://example.com/app.apk")))
        assertNull(findUpdate(foreign, UpdateChannel.STABLE, "0.1.0", 1))
    }
    @Test fun latestNumberedAssetAndChannelSpecificSixHourBoundary() {
        val release = nightlyRelease(10).copy(assets = nightlyRelease(9).assets + nightlyRelease(10).assets)
        assertEquals(10L, assetVersionCode(findUpdate(release, UpdateChannel.NIGHTLY, "0.1.0", 1)!!.asset.name))
        val preferences = UpdatePreferences(lastChecks = mapOf(UpdateChannel.STABLE to 100L))
        assertFalse(shouldCheckOnStartup(preferences, CHECK_INTERVAL_MS + 99))
        assertTrue(shouldCheckOnStartup(preferences, CHECK_INTERVAL_MS + 100))
        assertTrue(shouldCheckOnStartup(preferences.copy(channel = UpdateChannel.NIGHTLY), 101))
        assertFalse(shouldCheckOnStartup(preferences.copy(checkOnStartup = false), CHECK_INTERVAL_MS + 100))
        assertTrue(shouldCheckOnStartup(preferences, 1)) // clock moved backwards
    }
}
