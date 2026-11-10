package io.herdrmobile.android.ui.settings

import io.herdrmobile.android.R

enum class SettingsPage { TOP, HOSTS, NOTIFICATIONS, UPDATES, ABOUT, LICENSES }

// An empty stack means settings is closed. Direct entry still keeps the top as its parent.
fun openSettings(page: SettingsPage = SettingsPage.TOP): List<SettingsPage> =
    if (page == SettingsPage.TOP) listOf(page) else listOf(SettingsPage.TOP, page)

fun List<SettingsPage>.pushSettings(page: SettingsPage): List<SettingsPage> =
    if (lastOrNull() == page) this else this + page

fun List<SettingsPage>.popSettings(): List<SettingsPage> = dropLast(1)

data class SettingsSummary(val resource: Int, val arguments: List<Any> = emptyList())

fun hostSettingsSummary(count: Int): SettingsSummary =
    if (count == 0) SettingsSummary(R.string.add_host)
    else SettingsSummary(R.string.settings_host_count, listOf(count))

fun updateSettingsSummary(version: String, channelLabel: String, availableVersion: String?): SettingsSummary =
    if (availableVersion != null) SettingsSummary(R.string.update_available, listOf(availableVersion))
    else SettingsSummary(R.string.settings_version_channel, listOf(version, channelLabel))
