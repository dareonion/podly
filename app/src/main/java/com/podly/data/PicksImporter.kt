package com.podly.data

import com.podly.network.Http
import com.podly.network.ai.RecentEpisodeMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/**
 * A user-supplied list of episode picks, in the same shape as the generator's
 * recent-episodes files plus an optional playlist [name]. Extra JSON fields are
 * ignored, so a downloaded recs file imports as-is.
 */
@Serializable
data class PicksImportFile(
    val picks: List<CachedRecentEpisodePick>,
    val name: String? = null,
    val version: Int = 1,
    val generatedAtMs: Long = 0,
)

/** Outcome of a picks import: how many picks resolved, and which didn't. */
data class PicksImportResult(
    val playlistId: Long,
    val name: String,
    val saved: Int,
    val total: Int,
    val missed: List<String>,
)

/**
 * Imports a picks JSON file as a playlist: pulls each show's feed once, matches
 * every pick against the feed's episodes via [RecentEpisodeMatcher] (pick titles
 * are often paraphrases), and creates a playlist from the matches in file order.
 * Picks whose show or episode can't be found are skipped and reported as missed.
 */
class PicksImporter(
    private val podcasts: PodcastRepository,
    private val playlists: PlaylistRepository,
) {
    suspend fun import(json: String, fallbackName: String): PicksImportResult {
        val file = Http.json.decodeFromString<PicksImportFile>(json)
        require(file.picks.isNotEmpty()) { "No picks in this file." }

        // Lists often pick several episodes of one show — pull each feed once.
        val matches = coroutineScope {
            val gate = Semaphore(PodcastRepository.MAX_CONCURRENT_REFRESHES)
            file.picks.withIndex()
                .groupBy { (_, p) -> p.pick.podcastTitle.trim().lowercase() }
                .values
                .map { group -> async { gate.withPermit { matchGroup(group) } } }
                .awaitAll()
        }.flatten().sortedBy { (index, _) -> index }

        val matchedIds = matches.mapNotNull { (_, id) -> id }.distinct()
        require(matchedIds.isNotEmpty()) {
            "Couldn't match any of these picks to a podcast feed."
        }
        val missed = matches.filter { (_, id) -> id == null }
            .map { (index, _) -> file.picks[index].pick.episodeTitle }

        val name = file.name?.takeIf { it.isNotBlank() } ?: fallbackName
        val playlistId = playlists.create(name)
        matchedIds.forEach { playlists.addEpisode(playlistId, it) }
        return PicksImportResult(playlistId, name, matchedIds.size, file.picks.size, missed)
    }

    /** Resolves one show's picks to episode ids, keyed by pick index; null = no match. */
    private suspend fun matchGroup(
        group: List<IndexedValue<CachedRecentEpisodePick>>,
    ): List<Pair<Int, String?>> {
        val first = group.first().value
        val podcast = first.toPodcastOrNull()
            ?: podcasts.resolveByTitle(first.pick.podcastTitle)
            ?: return group.map { it.index to null }
        // A failed refresh (offline, blocked or vanished feed) shouldn't sink the
        // group when earlier pulls already cached the episodes.
        val loaded = runCatching { podcasts.openPodcast(podcast) }.getOrNull() ?: podcast
        val episodes = podcasts.episodesForPodcastOnce(loaded.id)
        val candidates = episodes.map {
            RecentEpisodeMatcher.Candidate(it.title, it.description, it.pubDateMs)
        }
        return group.map { (index, p) ->
            val match = RecentEpisodeMatcher.bestMatch(
                title = p.pick.episodeTitle,
                publishedApprox = p.pick.publishedApprox,
                candidates = candidates,
            )
            index to match?.let { episodes[it].id }
        }
    }
}
