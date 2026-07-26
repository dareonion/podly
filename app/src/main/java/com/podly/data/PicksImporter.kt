package com.podly.data

import com.podly.data.db.PodcastEntity
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
    private val rescuer: ArchiveRescuer,
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
        // Unrelated shows can share an exact title, so when the pick doesn't embed
        // its podcast, try each directory candidate and keep the first whose feed
        // actually contains a picked episode.
        val candidates = first.toPodcastOrNull()?.let(::listOf)
            ?: podcasts.resolveCandidatesByTitle(first.pick.podcastTitle)
        for (candidate in candidates) {
            // A failed refresh (offline, blocked or vanished feed) shouldn't sink
            // the group when earlier pulls already cached the episodes.
            val loaded = runCatching { podcasts.openPodcast(candidate) }.getOrNull() ?: candidate
            val fromFeed = matchAgainstFeed(group, loaded)
            if (fromFeed.any { (_, id) -> id != null }) {
                return rescueMissing(loaded, group, fromFeed)
            }
        }
        // No candidate's feed matched anything (short window, or the feed is
        // unfetchable); rescue against each candidate's archive in turn.
        for (candidate in candidates) {
            val rescued = rescuer.rescue(
                candidate,
                group.map { (index, p) ->
                    index to ArchiveRescuer.Query(p.pick.episodeTitle, p.pick.publishedApprox)
                },
            )
            if (rescued.isNotEmpty()) return group.map { (index, _) -> index to rescued[index] }
        }
        return group.map { it.index to null }
    }

    private suspend fun matchAgainstFeed(
        group: List<IndexedValue<CachedRecentEpisodePick>>,
        podcast: PodcastEntity,
    ): List<Pair<Int, String?>> {
        val episodes = podcasts.episodesForPodcastOnce(podcast.id)
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

    private suspend fun rescueMissing(
        podcast: PodcastEntity,
        group: List<IndexedValue<CachedRecentEpisodePick>>,
        fromFeed: List<Pair<Int, String?>>,
    ): List<Pair<Int, String?>> {
        val missing = group.filter { (index, _) -> fromFeed.first { it.first == index }.second == null }
        if (missing.isEmpty()) return fromFeed
        val rescued = rescuer.rescue(
            podcast,
            missing.map { (index, p) ->
                index to ArchiveRescuer.Query(p.pick.episodeTitle, p.pick.publishedApprox)
            },
        )
        return fromFeed.map { (index, id) -> index to (id ?: rescued[index]) }
    }
}
