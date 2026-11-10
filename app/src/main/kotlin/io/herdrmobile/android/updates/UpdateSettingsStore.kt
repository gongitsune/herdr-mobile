package io.herdrmobile.android.updates

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.updateData by preferencesDataStore("updates")
class UpdateSettingsStore(context: Context) : UpdateSettings {
    private val store = context.applicationContext.updateData
    private val channelKey = stringPreferencesKey("channel")
    private val startupKey = booleanPreferencesKey("startup")
    private fun checkKey(channel: UpdateChannel) = longPreferencesKey("last_check_${channel.name}")
    override val preferences = store.data.map { values ->
        UpdatePreferences(
            channel = UpdateChannel.entries.find { it.name == values[channelKey] } ?: UpdateChannel.STABLE,
            checkOnStartup = values[startupKey] ?: true,
            lastChecks = UpdateChannel.entries.mapNotNull { channel -> values[checkKey(channel)]?.let { channel to it } }.toMap(),
        )
    }
    override suspend fun setChannel(channel: UpdateChannel) { store.edit { it[channelKey] = channel.name } }
    override suspend fun setCheckOnStartup(enabled: Boolean) { store.edit { it[startupKey] = enabled } }
    override suspend fun recordCheck(channel: UpdateChannel, at: Long) { store.edit { it[checkKey(channel)] = at } }
}
