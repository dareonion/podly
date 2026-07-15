package com.podly.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class AiProvider { CLAUDE, OPENAI }

data class Settings(
    val aiProvider: AiProvider = AiProvider.CLAUDE,
    val anthropicApiKey: String = "",
    val openAiApiKey: String = "",
    val podcastIndexKey: String = "",
    val podcastIndexSecret: String = "",
    val seekBackSeconds: Int = 10,
    val seekForwardSeconds: Int = 30,
    val downloadWifiOnly: Boolean = false,
    /** Auto-download this many newest episodes per subscribed podcast; 0 = off. */
    val autoDownloadCount: Int = 0,
    val autoDeleteCompleted: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val ANTHROPIC_KEY = stringPreferencesKey("anthropic_api_key")
        val OPENAI_KEY = stringPreferencesKey("openai_api_key")
        val PI_KEY = stringPreferencesKey("podcastindex_key")
        val PI_SECRET = stringPreferencesKey("podcastindex_secret")
        val SEEK_BACK = intPreferencesKey("seek_back_seconds")
        val SEEK_FORWARD = intPreferencesKey("seek_forward_seconds")
        val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only")
        val AUTO_DOWNLOAD_COUNT = intPreferencesKey("auto_download_count")
        val AUTO_DELETE_COMPLETED = booleanPreferencesKey("auto_delete_completed")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            aiProvider = prefs[Keys.AI_PROVIDER]?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
                ?: AiProvider.CLAUDE,
            anthropicApiKey = prefs[Keys.ANTHROPIC_KEY] ?: "",
            openAiApiKey = prefs[Keys.OPENAI_KEY] ?: "",
            podcastIndexKey = prefs[Keys.PI_KEY] ?: "",
            podcastIndexSecret = prefs[Keys.PI_SECRET] ?: "",
            seekBackSeconds = prefs[Keys.SEEK_BACK] ?: 10,
            seekForwardSeconds = prefs[Keys.SEEK_FORWARD] ?: 30,
            downloadWifiOnly = prefs[Keys.DOWNLOAD_WIFI_ONLY] ?: false,
            autoDownloadCount = prefs[Keys.AUTO_DOWNLOAD_COUNT] ?: 0,
            autoDeleteCompleted = prefs[Keys.AUTO_DELETE_COMPLETED] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setAiProvider(provider: AiProvider) =
        context.dataStore.edit { it[Keys.AI_PROVIDER] = provider.name }

    suspend fun setAnthropicApiKey(key: String) =
        context.dataStore.edit { it[Keys.ANTHROPIC_KEY] = key.trim() }

    suspend fun setOpenAiApiKey(key: String) =
        context.dataStore.edit { it[Keys.OPENAI_KEY] = key.trim() }

    suspend fun setPodcastIndexCreds(key: String, secret: String) =
        context.dataStore.edit {
            it[Keys.PI_KEY] = key.trim()
            it[Keys.PI_SECRET] = secret.trim()
        }

    suspend fun setSeekIncrements(backSeconds: Int, forwardSeconds: Int) =
        context.dataStore.edit {
            it[Keys.SEEK_BACK] = backSeconds
            it[Keys.SEEK_FORWARD] = forwardSeconds
        }

    suspend fun setDownloadWifiOnly(wifiOnly: Boolean) =
        context.dataStore.edit { it[Keys.DOWNLOAD_WIFI_ONLY] = wifiOnly }

    suspend fun setAutoDownloadCount(count: Int) =
        context.dataStore.edit { it[Keys.AUTO_DOWNLOAD_COUNT] = count.coerceAtLeast(0) }

    suspend fun setAutoDeleteCompleted(enabled: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_DELETE_COMPLETED] = enabled }
}
