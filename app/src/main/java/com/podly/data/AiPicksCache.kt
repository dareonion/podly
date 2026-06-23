package com.podly.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.podly.data.db.PodcastEntity
import com.podly.network.Http
import com.podly.network.ai.AiAcclaimedPick
import com.podly.network.ai.AiEpisodePick
import com.podly.network.ai.AiRecentEpisodePick
import com.podly.network.ai.RecentEpisodeWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** A resolved acclaimed pick flattened into a JSON-friendly shape. */
@Serializable
data class CachedAcclaimedPick(
    val pick: AiAcclaimedPick,
    val podcastId: String? = null,
    val podcastTitle: String? = null,
    val podcastAuthor: String? = null,
    val feedUrl: String? = null,
    val artworkUrl: String? = null,
    val podcastDescription: String? = null,
) {
    fun toPodcastOrNull(): PodcastEntity? =
        if (podcastId != null && podcastTitle != null && podcastAuthor != null && feedUrl != null) {
            PodcastEntity(
                id = podcastId,
                title = podcastTitle,
                author = podcastAuthor,
                feedUrl = feedUrl,
                artworkUrl = artworkUrl,
                description = podcastDescription,
            )
        } else {
            null
        }

    companion object {
        fun of(pick: AiAcclaimedPick, podcast: PodcastEntity?) = CachedAcclaimedPick(
            pick = pick,
            podcastId = podcast?.id,
            podcastTitle = podcast?.title,
            podcastAuthor = podcast?.author,
            feedUrl = podcast?.feedUrl,
            artworkUrl = podcast?.artworkUrl,
            podcastDescription = podcast?.description,
        )
    }
}

@Serializable
data class CachedAcclaimed(
    val picks: List<CachedAcclaimedPick>,
    val fetchedAtMs: Long,
)

/** "Where to start" picks for one podcast; episodes are re-matched against the feed on load. */
@Serializable
data class CachedEpisodePicks(
    val picks: List<AiEpisodePick>,
    val fetchedAtMs: Long,
)

@Serializable
data class CachedRecentEpisodePick(
    val pick: AiRecentEpisodePick,
    val podcastId: String? = null,
    val podcastTitle: String? = null,
    val podcastAuthor: String? = null,
    val feedUrl: String? = null,
    val artworkUrl: String? = null,
    val podcastDescription: String? = null,
) {
    fun toPodcastOrNull(): PodcastEntity? =
        if (podcastId != null && podcastTitle != null && podcastAuthor != null && feedUrl != null) {
            PodcastEntity(
                id = podcastId,
                title = podcastTitle,
                author = podcastAuthor,
                feedUrl = feedUrl,
                artworkUrl = artworkUrl,
                description = podcastDescription,
            )
        } else {
            null
        }

    companion object {
        fun of(pick: AiRecentEpisodePick, podcast: PodcastEntity?) = CachedRecentEpisodePick(
            pick = pick,
            podcastId = podcast?.id,
            podcastTitle = podcast?.title,
            podcastAuthor = podcast?.author,
            feedUrl = podcast?.feedUrl,
            artworkUrl = podcast?.artworkUrl,
            podcastDescription = podcast?.description,
        )
    }
}

@Serializable
data class CachedRecentEpisodes(
    val picks: List<CachedRecentEpisodePick>,
    val fetchedAtMs: Long,
)

private val Context.aiPicksDataStore by preferencesDataStore(name = "ai_picks_cache")

/** Persists the last "acclaimed" AI result so reopening Discover doesn't re-query the API. */
class AiPicksCache(private val context: Context) {

    private object Keys {
        val ACCLAIMED_JSON = stringPreferencesKey("acclaimed_json")
    }

    suspend fun loadAcclaimed(): CachedAcclaimed? =
        context.aiPicksDataStore.data.first()[Keys.ACCLAIMED_JSON]?.let { json ->
            runCatching { Http.json.decodeFromString<CachedAcclaimed>(json) }.getOrNull()
        }

    suspend fun saveAcclaimed(cache: CachedAcclaimed) {
        val json = Http.json.encodeToString(CachedAcclaimed.serializer(), cache)
        context.aiPicksDataStore.edit { it[Keys.ACCLAIMED_JSON] = json }
    }

    suspend fun loadStarters(podcastId: String): CachedEpisodePicks? =
        context.aiPicksDataStore.data.first()[startersKey(podcastId)]?.let { json ->
            runCatching { Http.json.decodeFromString<CachedEpisodePicks>(json) }.getOrNull()
        }

    suspend fun saveStarters(podcastId: String, cache: CachedEpisodePicks) {
        val json = Http.json.encodeToString(CachedEpisodePicks.serializer(), cache)
        context.aiPicksDataStore.edit { it[startersKey(podcastId)] = json }
    }

    suspend fun loadRecentEpisodes(window: RecentEpisodeWindow): CachedRecentEpisodes? =
        context.aiPicksDataStore.data.first()[recentEpisodesKey(window)]?.let { json ->
            runCatching { Http.json.decodeFromString<CachedRecentEpisodes>(json) }.getOrNull()
        }

    /** Emits whenever the cached result for [window] changes — drives the Discover UI. */
    fun recentEpisodesFlow(window: RecentEpisodeWindow): Flow<CachedRecentEpisodes?> =
        context.aiPicksDataStore.data.map { prefs ->
            prefs[recentEpisodesKey(window)]?.let { json ->
                runCatching { Http.json.decodeFromString<CachedRecentEpisodes>(json) }.getOrNull()
            }
        }

    suspend fun saveRecentEpisodes(window: RecentEpisodeWindow, cache: CachedRecentEpisodes) {
        val json = Http.json.encodeToString(CachedRecentEpisodes.serializer(), cache)
        context.aiPicksDataStore.edit { it[recentEpisodesKey(window)] = json }
    }

    private fun startersKey(podcastId: String) = stringPreferencesKey("starters_$podcastId")

    private fun recentEpisodesKey(window: RecentEpisodeWindow) =
        stringPreferencesKey("recent_episodes_${window.name}")
}
