package io.herdrmobile.android.ui.settings

import io.herdrmobile.android.R
import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun navigateToLicensesAndBackOneLevelAtATime() {
        val top = openSettings()
        val about = top.pushSettings(SettingsPage.ABOUT)
        val licenses = about.pushSettings(SettingsPage.LICENSES)
        assertEquals(listOf(SettingsPage.TOP, SettingsPage.ABOUT, SettingsPage.LICENSES), licenses)
        assertEquals(about, licenses.popSettings())
        assertEquals(top, licenses.popSettings().popSettings())
        assertTrue(licenses.popSettings().popSettings().popSettings().isEmpty())
        assertEquals(listOf(SettingsPage.TOP), top)
    }

    @Test fun directHostEntryStillReturnsToTopBeforeClosing() {
        val hosts = openSettings(SettingsPage.HOSTS)
        assertEquals(listOf(SettingsPage.TOP, SettingsPage.HOSTS), hosts)
        assertEquals(openSettings(), hosts.popSettings())
        assertTrue(hosts.popSettings().popSettings().isEmpty())
    }

    @Test fun repeatedEntryDoesNotDuplicateTheCurrentPage() {
        val updates = openSettings().pushSettings(SettingsPage.UPDATES)
        assertEquals(updates, updates.pushSettings(SettingsPage.UPDATES))
        assertTrue(emptyList<SettingsPage>().popSettings().isEmpty())
    }

    @Test fun noHostsPromptsToAddAndSavedHostsShowTheirCount() {
        assertEquals(SettingsSummary(R.string.add_host), hostSettingsSummary(0))
        assertEquals(SettingsSummary(R.string.settings_host_count, listOf(1)), hostSettingsSummary(1))
        assertEquals(SettingsSummary(R.string.settings_host_count, listOf(7)), hostSettingsSummary(7))
    }

    @Test fun noUpdateShowsInstalledVersionAndSelectedChannel() {
        assertEquals(SettingsSummary(R.string.settings_version_channel, listOf("0.1.0", "安定版")),
            updateSettingsSummary("0.1.0", "安定版", null))
        assertEquals(SettingsSummary(R.string.settings_version_channel, listOf("0.1.0", "Nightly")),
            updateSettingsSummary("0.1.0", "Nightly", null))
    }

    @Test fun availableUpdateTakesPrecedenceOverInstalledVersion() {
        assertEquals(SettingsSummary(R.string.update_available, listOf("v0.2.0")),
            updateSettingsSummary("0.1.0", "Stable", "v0.2.0"))
        assertEquals(SettingsSummary(R.string.update_available, listOf("nightly")),
            updateSettingsSummary("0.1.0", "Nightly", "nightly"))
    }
}
