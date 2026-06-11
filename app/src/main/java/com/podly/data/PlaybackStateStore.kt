package com.podly.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

data class SavedQueue(val episodeIds: List<String>, val currentIndex: Int)

private val Context.playbackDataStore by preferencesDataStore(name = "playback_state")

/**
 * Remembers the last playback queue so Android Auto's "resume" entry point
 * (onPlaybackResumption) can restore it after the process dies.
 */
class PlaybackStateStore(private val context: Context) {

    private object Keys {
        val QUEUE_IDS = stringPreferencesKey("queue_episode_ids")
        val QUEUE_INDEX = intPreferencesKey("queue_index")
    }

    suspend fun save(episodeIds: List<String>, currentIndex: Int) {
        context.playbackDataStore.edit { prefs ->
            prefs[Keys.QUEUE_IDS] = episodeIds.joinToString(SEPARATOR)
            prefs[Keys.QUEUE_INDEX] = currentIndex
        }
    }

    suspend fun load(): SavedQueue? {
        val prefs = context.playbackDataStore.data.first()
        val ids = prefs[Keys.QUEUE_IDS]?.split(SEPARATOR)?.filter { it.isNotBlank() }
        if (ids.isNullOrEmpty()) return null
        val index = (prefs[Keys.QUEUE_INDEX] ?: 0).coerceIn(0, ids.size - 1)
        return SavedQueue(ids, index)
    }

    companion object {
        private const val SEPARATOR = ","
    }
}
