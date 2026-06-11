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
)

class ItunesApi {
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
        }
    }
}
