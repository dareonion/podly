package com.podly.data

import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PodcastEntity
import com.podly.data.db.stableId
import com.podly.network.ai.RecentEpisodeMatcher

/**
 * Recovers picks that have rolled off a show's short RSS feed (e.g. "The Interview",
 * which exposes only its newest episodes) by looking them up in an episode archive and
 * inserting the episode rows directly so they play like any feed episode.
 *
 * Tries [archives] in order and uses the first that returns episodes for the show, so a
 * provider that's unconfigured, errored, or rate-limited is skipped for the next one.
 * Returns an empty map when no configured provider can supply the show. Shared by the
 * picks importer and the Discover "save as playlist" flow.
 */
class ArchiveRescuer(
    private val podcasts: PodcastRepository,
    private val archives: List<EpisodeArchive>,
) {
    /** An AI pick to look up: its (often paraphrased) episode title + approximate date. */
    data class Query(val episodeTitle: String, val publishedApprox: String?)

    /**
     * Resolves [missing] picks (keyed by [K]) against [podcast]'s archive; key -> inserted
     * episode id for the ones found. One archive call per provider per podcast.
     */
    suspend fun <K> rescue(podcast: PodcastEntity, missing: List<Pair<K, Query>>): Map<K, String> {
        if (missing.isEmpty()) return emptyMap()
        val items = archives.firstNotNullOfOrNull { archive ->
            archive.episodesByFeedUrl(podcast.feedUrl)?.takeIf { it.isNotEmpty() }
        } ?: return emptyMap()
        val candidates = items.map {
            RecentEpisodeMatcher.Candidate(it.title.orEmpty(), it.description, it.datePublishedMs)
        }
        val rescued = mutableMapOf<K, String>()
        val toInsert = mutableListOf<EpisodeEntity>()
        for ((key, q) in missing) {
            val idx = RecentEpisodeMatcher.bestMatch(q.episodeTitle, q.publishedApprox, candidates) ?: continue
            val item = items[idx]
            val audioUrl = item.audioUrl ?: continue
            val episode = EpisodeEntity(
                id = stableId(item.guid ?: audioUrl),
                podcastId = podcast.id,
                podcastTitle = podcast.title,
                guid = item.guid,
                title = item.title ?: q.episodeTitle,
                description = item.description,
                audioUrl = audioUrl,
                pubDateMs = item.datePublishedMs,
                durationMs = item.durationMs,
                artworkUrl = item.imageUrl?.ifBlank { null } ?: podcast.artworkUrl,
            )
            toInsert += episode
            rescued[key] = episode.id
        }
        if (toInsert.isNotEmpty()) podcasts.insertEpisodes(toInsert)
        return rescued
    }
}
