package com.podly.data.radio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.podly.radio.RadioProfiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Radio preferences: which profile is selected, and which shows each profile may use.
 *
 * Its own DataStore rather than [com.podly.data.SettingsRepository]: that one is
 * scalar-only, and it is deliberately excluded from cloud backup because it holds
 * plaintext API keys. Radio preferences hold no secrets and should be backed up, so
 * putting them there would quietly opt them out of restore.
 */
private val Context.radioDataStore by preferencesDataStore(name = "radio")

class RadioProfileStore(private val context: Context) {

    private object Keys {
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile_id")
        fun shows(profileId: String) = stringPreferencesKey("shows_$profileId")
    }

    val activeProfileId: Flow<String> = context.radioDataStore.data
        .map { it[Keys.ACTIVE_PROFILE] ?: RadioProfiles.DEFAULT_ID }

    suspend fun currentProfileId(): String = activeProfileId.first()

    suspend fun setActiveProfileId(id: String) {
        context.radioDataStore.edit { it[Keys.ACTIVE_PROFILE] = id }
    }

    /** Podcast ids a profile is allowed to draw its backlog from. */
    fun selectedShows(profileId: String): Flow<Set<String>> = context.radioDataStore.data
        .map { prefs -> decode(prefs[Keys.shows(profileId)]) }

    suspend fun selectedShowsOnce(profileId: String): Set<String> =
        selectedShows(profileId).first()

    suspend fun setSelectedShows(profileId: String, podcastIds: Set<String>) {
        context.radioDataStore.edit {
            it[Keys.shows(profileId)] = podcastIds.joinToString(SEPARATOR)
        }
    }

    private fun decode(raw: String?): Set<String> =
        raw?.split(SEPARATOR)?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private companion object {
        const val SEPARATOR = ","
    }
}
