package com.podly.playback

import androidx.media3.common.MediaItem
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.EpisodeSearchRow
import com.podly.data.db.PodlyDatabase
import com.podly.data.db.SubscribedPodcast
import com.podly.ui.library.SubscriptionSort
import com.podly.ui.library.filterSubscriptions
import com.podly.ui.library.sortName
import java.text.Collator

/**
 * Search for Android Auto: its search button, and a spoken "play … on Podly".
 *
 * A query matches a subscribed show when every word is in its title or author (as on
 * the phone's Your podcasts page), and a library episode when every word is in its
 * title or show name. Words split on any whitespace, the full-width space included;
 * Chinese has no spaces, so a Chinese query is one phrase that must appear as typed.
 */
internal class CarSearch(
    private val database: PodlyDatabase,
    private val collator: () -> Comparator<Any?> = { Collator.getInstance() },
) {

    /** Matching shows (to browse into), recently played first, then matching episodes, newest first. */
    suspend fun results(query: String): List<MediaItem> {
        val words = searchWords(query)
        if (words.isEmpty()) return emptyList()
        val shows = filterSubscriptions(
            database.podcastDao().subscribedWithLastPlayedOnce(), query, SubscriptionSort.RECENT, collator(),
        ).take(MAX_RESULTS)
        val rows = matchingEpisodes(database.episodeDao().librarySearchRows(), words).take(MAX_RESULTS - shows.size)
        val episodes = lookUpInOrder(rows.map { it.id }, EpisodeEntity::id) { database.episodeDao().byIds(it) }
        return shows.map { MediaItemFactory.podcast(it.podcast) } +
            rows.zip(episodes).mapNotNull { (row, episode) ->
                // An episode of a subscribed show plays on through it, as from the show's
                // own list; one saved from a show you don't follow plays alone.
                episode?.let {
                    MediaItemFactory.browsableEpisode(
                        it,
                        if (row.subscribed) MediaIds.showEpisode(it.id) else MediaIds.episode(it.id),
                    )
                }
            }
    }

    /**
     * The queue a spoken "play [query]" starts, as episode ids, or null when nothing fits
     * (the request then fails and whatever is playing carries on). Candidates are tried
     * in [voiceCandidates] order until one can play: a show plays from its unfinished
     * episode heard most recently, else its first unfinished one in its own order, and
     * on through the show; a show with nothing unfinished can't play.
     */
    suspend fun voiceQueue(query: String): List<String>? {
        val dao = database.episodeDao()
        val candidates = voiceCandidates(
            query, database.podcastDao().subscribedWithLastPlayedOnce(), dao.librarySearchRows(), collator(),
        )
        for (pick in candidates) {
            val queue = when (pick) {
                is VoicePick.Show -> {
                    val order = dao.episodeOrderForPodcast(pick.podcastId)
                    val start = dao.inProgressEpisodeForPodcast(pick.podcastId)
                        ?: order.firstOrNull { !it.completed }?.id
                    start?.let { continueThroughShow(order, it) }
                }
                is VoicePick.Episode ->
                    if (pick.row.subscribed) continueThroughShow(dao.episodeOrderForPodcast(pick.row.podcastId), pick.row.id)
                    else listOf(pick.row.id)
            }
            if (queue != null) return queue
        }
        return null
    }

    companion object {
        const val MAX_RESULTS = 50
    }
}

internal sealed interface VoicePick {
    data class Show(val podcastId: String) : VoicePick
    data class Episode(val row: EpisodeSearchRow) : VoicePick
}

/** [query]'s words: split on any whitespace, the full-width space a Chinese keyboard types included. */
internal fun searchWords(query: String): List<String> =
    query.trim().split(Regex("\\p{javaWhitespace}+")).filter { it.isNotEmpty() }

/** Episodes whose title or show name holds every word, newest first. */
internal fun matchingEpisodes(rows: List<EpisodeSearchRow>, words: List<String>): List<EpisodeSearchRow> =
    rows.filter { row ->
        val haystack = "${row.title} ${row.podcastTitle}"
        words.all { haystack.contains(it, ignoreCase = true) }
    }.sortedWith(compareByDescending<EpisodeSearchRow> { it.pubDateMs }.thenBy { it.id })

/**
 * What a spoken query may name, likeliest first. A show titled exactly that is the only
 * candidate when there is one, so a show heard to the end leaves playback alone rather
 * than starting something else. Otherwise: episodes titled exactly that, shows matching
 * every word (recently played first), then episodes matching every word (newest first)
 * from shows not already offered. Lazy, so a match that plays stops it.
 */
internal fun voiceCandidates(
    query: String,
    shows: List<SubscribedPodcast>,
    rows: List<EpisodeSearchRow>,
    collator: Comparator<Any?>,
): Sequence<VoicePick> {
    val words = searchWords(query)
    if (words.isEmpty()) return emptySequence()
    val wanted = comparableTitle(query)
    val exactShows = shows.filter { comparableTitle(it.podcast.title) == wanted }
    if (exactShows.isNotEmpty()) {
        return exactShows.sortedByDescending { it.lastPlayedAt }.asSequence().map { VoicePick.Show(it.podcast.id) }
    }
    val partialShows = filterSubscriptions(shows, query, SubscriptionSort.RECENT, collator)
    val offered = partialShows.map { it.podcast.id }.toSet()
    return sequence {
        yieldAll(
            rows.filter { comparableTitle(it.title) == wanted }
                .sortedByDescending { it.pubDateMs }
                .map { VoicePick.Episode(it) }
        )
        yieldAll(partialShows.map { VoicePick.Show(it.podcast.id) })
        yieldAll(
            matchingEpisodes(rows, words).asSequence()
                .filterNot { it.podcastId in offered }
                .map { VoicePick.Episode(it) }
        )
    }
}

/** A title as speech gives it back: no leading symbols, single spaces, any case. */
internal fun comparableTitle(title: String): String =
    searchWords(sortName(title.trim())).joinToString(" ").lowercase()
