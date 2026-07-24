package com.podly.data

import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PodcastEntity
import com.podly.data.db.stableId
import com.podly.network.PodcastIndexApi
import com.podly.network.ai.RecentEpisodeMatcher
import kotlinx.coroutines.flow.first

/**
 * PodcastIndex retains episodes after they roll off a short feed window (e.g. shows
 * like "The Interview" that expose only their two newest episodes). When the user's
 * PodcastIndex creds are configured, this looks missing picks up in that archive and
 * inserts the episode rows directly so they play like any feed episode.
 *
 * Shared by the picks importer and the Discover "save as playlist" flow so both
 * resolve rolled-off picks the same way.
 */
class PodcastIndexRescuer(
    private val podcasts: PodcastRepository,
    private val podcastIndex: PodcastIndexApi,
    private val settings: SettingsRepository,
) {
    /** An AI pick to look up: its (often paraphrased) episode title + approximate date. */
    data class Query(val episodeTitle: String, val publishedApprox: String?)

    /**
     * Resolves [missing] picks (keyed by [K]) against [podcast]'s PodcastIndex archive.
     * Returns key -> inserted episode id for the ones found; a missing key means it
     * wasn't matched (or creds/network were unavailable). One archive call per podcast.
     */
    suspend fun <K> rescue(podcast: PodcastEntity, missing: List<Pair<K, Query>>): Map<K, String> {
        if (missing.isEmpty()) return emptyMap()
        val s = settings.settings.first()
        if (s.podcastIndexKey.isBlank() || s.podcastIndexSecret.isBlank()) return emptyMap()
        val items = runCatching {
            podcastIndex.episodesByFeedUrl(s.podcastIndexKey, s.podcastIndexSecret, podcast.feedUrl)
        }.getOrNull() ?: return emptyMap()
        val candidates = items.map {
            RecentEpisodeMatcher.Candidate(it.title.orEmpty(), it.description, it.datePublished * 1000)
        }
        val rescued = mutableMapOf<K, String>()
        val toInsert = mutableListOf<EpisodeEntity>()
        for ((key, q) in missing) {
            val idx = RecentEpisodeMatcher.bestMatch(q.episodeTitle, q.publishedApprox, candidates) ?: continue
            val item = items[idx]
            val audioUrl = item.enclosureUrl ?: continue
            val episode = EpisodeEntity(
                id = stableId(item.guid ?: audioUrl),
                podcastId = podcast.id,
                podcastTitle = podcast.title,
                guid = item.guid,
                title = item.title ?: q.episodeTitle,
                description = item.description,
                audioUrl = audioUrl,
                pubDateMs = item.datePublished * 1000,
                durationMs = item.duration?.times(1000),
                artworkUrl = item.image?.ifBlank { null } ?: podcast.artworkUrl,
            )
            toInsert += episode
            rescued[key] = episode.id
        }
        if (toInsert.isNotEmpty()) podcasts.insertEpisodes(toInsert)
        return rescued
    }
}
