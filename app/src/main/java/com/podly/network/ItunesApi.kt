package com.podly.network

import com.podly.data.db.PodcastEntity
import com.podly.data.db.stableId
import kotlinx.serialization.Serializable
import java.net.URLEncoder

@Serializable
private data class ItunesSearchResponse(val results: List<ItunesResult> = emptyList())

@Serializable
private data class ItunesResult(
    val collectionName: String? = null,
    val artistName: String? = null,
    val feedUrl: String? = null,
    val artworkUrl600: String? = null,
    val artworkUrl100: String? = null,
    val primaryGenreName: String? = null,
    val genres: List<String> = emptyList(),
)

class ItunesApi {
    /**
     * The directory's genres for a show already known by feed URL.
     *
     * The fallback for feeds that declare no `<itunes:category>` — SoundOn's do
     * not, and one of those is a children's show that would otherwise look
     * uncategorised to a radio profile that excludes them. Matched on feedUrl,
     * never on the first search hit: several shows share a title.
     */
    suspend fun genresForFeed(title: String, feedUrl: String): List<String> {
        if (title.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(title, "UTF-8")
        val body = Http.get("https://itunes.apple.com/search?media=podcast&limit=50&term=$encoded")
        val wanted = feedUrl.normalizedFeedUrl()
        return Http.json.decodeFromString<ItunesSearchResponse>(body).results
            .firstOrNull { it.feedUrl?.normalizedFeedUrl() == wanted }
            ?.genres
            .orEmpty()
            // "Podcasts" is on nearly every show and says nothing.
            .filter { !it.equals("Podcasts", ignoreCase = true) }
    }

    suspend fun searchPodcasts(term: String): List<PodcastEntity> {
        val encoded = URLEncoder.encode(term, "UTF-8")
        val body = Http.get("https://itunes.apple.com/search?media=podcast&limit=50&term=$encoded")
        return Http.json.decodeFromString<ItunesSearchResponse>(body).results.mapNotNull { result ->
            val feedUrl = result.feedUrl ?: return@mapNotNull null
            PodcastEntity(
                id = stableId(feedUrl),
                title = result.collectionName ?: return@mapNotNull null,
                author = result.artistName ?: "",
                feedUrl = feedUrl,
                artworkUrl = result.artworkUrl600 ?: result.artworkUrl100,
                description = result.primaryGenreName,
            )
        // Apple sometimes returns the same feed more than once; collapse to one
        // entry per id so list keys stay unique (duplicate keys crash LazyColumn).
        }.distinctBy { it.id }
    }
}

/** Feed URLs differ by scheme and trailing slash across Apple and the feed itself. */
private fun String.normalizedFeedUrl(): String =
    trim().removePrefix("https://").removePrefix("http://").removeSuffix("/").lowercase()
