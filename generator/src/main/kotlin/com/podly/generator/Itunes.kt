package com.podly.generator

import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.security.MessageDigest

/** A podcast resolved from the iTunes directory, carrying everything the app row needs. */
data class ResolvedPodcast(
    val podcastId: String,
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String?,
    val description: String?,
)

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

/**
 * Mirrors the app's iTunes resolution (`ItunesApi.searchPodcasts` +
 * `RecentEpisodesWorker.resolve`) so the published picks carry the same
 * `podcastId` / `feedUrl` / artwork the on-device worker used to produce.
 */
object Itunes {
    /** Resolves [title] to a podcast: exact-title match if present, else the first hit. */
    fun resolve(title: String): ResolvedPodcast? {
        val candidates = search(title)
        return candidates.firstOrNull { it.title.equals(title, ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    private fun search(term: String): List<ResolvedPodcast> {
        val encoded = URLEncoder.encode(term, "UTF-8")
        val body = runCatching {
            Net.get("https://itunes.apple.com/search?media=podcast&limit=50&term=$encoded")
        }.getOrElse { return emptyList() }
        return json.decodeFromString<ItunesSearchResponse>(body).results.mapNotNull { r ->
            val feedUrl = r.feedUrl ?: return@mapNotNull null
            val name = r.collectionName ?: return@mapNotNull null
            ResolvedPodcast(
                podcastId = stableId(feedUrl),
                title = name,
                author = r.artistName ?: "",
                feedUrl = feedUrl,
                artworkUrl = r.artworkUrl600 ?: r.artworkUrl100,
                description = r.primaryGenreName,
            )
        // Apple sometimes returns the same feed twice; collapse to one per id.
        }.distinctBy { it.podcastId }
    }
}

/** Mirrors the app's `stableId` (data/db/Entities.kt): SHA-256 hex, first 32 chars. */
fun stableId(raw: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32)
