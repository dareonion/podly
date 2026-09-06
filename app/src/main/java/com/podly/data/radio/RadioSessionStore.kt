package com.podly.data.radio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A radio session: which profile is listening, and since when. */
data class RadioSession(val profileId: String, val startedAtMs: Long)

private val Context.radioSessionDataStore by preferencesDataStore(name = "radio_session")

/**
 * Which profile the *current playback session* belongs to.
 *
 * Deliberately not "whichever profile the chip is showing": listening must be
 * attributed to the session that started it, or manually tapping an episode while the
 * toddler profile happens to be selected would file it as the toddler's.
 *
 * [currentProfileIdOrNull] is a plain volatile read because the listening-segment
 * flush in PlaybackService cannot suspend on DataStore — the same reason the service
 * mirrors its active player into a `@Volatile` field.
 */
class RadioSessionStore(private val context: Context, scope: CoroutineScope) {

    private val _active = MutableStateFlow<RadioSession?>(null)
    val active: StateFlow<RadioSession?> = _active

    @Volatile private var profileId: String? = null

    init {
        scope.launch {
            val prefs = context.radioSessionDataStore.data.first()
            prefs[KEY_PROFILE]?.let { restored ->
                profileId = restored
                _active.value = RadioSession(restored, System.currentTimeMillis())
            }
        }
    }

    fun currentProfileIdOrNull(): String? = profileId

    suspend fun start(profileId: String) {
        this.profileId = profileId
        _active.value = RadioSession(profileId, System.currentTimeMillis())
        context.radioSessionDataStore.edit { it[KEY_PROFILE] = profileId }
    }

    suspend fun end() {
        profileId = null
        _active.value = null
        context.radioSessionDataStore.edit { it.remove(KEY_PROFILE) }
    }

    private companion object {
        val KEY_PROFILE = stringPreferencesKey("session_profile_id")
    }
}
