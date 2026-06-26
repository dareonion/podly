package com.podly.network

import com.podly.data.CachedAcclaimed
import com.podly.data.CachedRecentEpisodes
import com.podly.network.ai.RecentEpisodeWindow

/**
 * Fetches the pre-generated recommendation files published to GitHub Pages by the
 * `:generator` GitHub Action. These replace the on-device Claude calls for the
 * recent-episode and acclaimed lists, which need no per-user data.
 *
 * The files deserialize straight into [CachedRecentEpisodes] / [CachedAcclaimed];
 * the caller stamps `fetchedAtMs` and persists them in `AiPicksCache`.
 */
class RemoteRecsApi {
    suspend fun recentEpisodes(window: RecentEpisodeWindow): CachedRecentEpisodes =
        Http.json.decodeFromString(Http.get(BASE_URL + fileFor(window)))

    suspend fun acclaimed(): CachedAcclaimed =
        Http.json.decodeFromString(Http.get(BASE_URL + ACCLAIMED_FILE))

    private fun fileFor(window: RecentEpisodeWindow) = when (window) {
        RecentEpisodeWindow.TWO_WEEKS -> "recent-2weeks.json"
        RecentEpisodeWindow.MONTH -> "recent-month.json"
        RecentEpisodeWindow.THREE_MONTHS -> "recent-3months.json"
    }

    companion object {
        const val BASE_URL = "https://dareonion.github.io/podly/"
        private const val ACCLAIMED_FILE = "acclaimed.json"
    }
}
