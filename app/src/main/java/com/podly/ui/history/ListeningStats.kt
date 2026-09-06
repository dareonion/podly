package com.podly.ui.history

import com.podly.data.db.EpisodeHistorySummary
import com.podly.data.db.ListeningSegmentEntity

data class ShowListening(val podcastTitle: String, val listenedMs: Long)

/** Which profile's listening to count. */
sealed interface ProfileFilter {
    data object All : ProfileFilter
    /** A null [profileId] means untagged listening — anything outside radio. */
    data class Only(val profileId: String?) : ProfileFilter
}

data class ListeningStats(
    val totalMs: Long,
    val last7DaysMs: Long,
    val last30DaysMs: Long,
    val episodesTouched: Int,
    val episodesCompleted: Int,
    /** Shows by total time listened, descending. */
    val topShows: List<ShowListening>,
    /** Time per profile, always over *all* segments so the chips keep their totals. */
    val msByProfile: Map<String?, Long> = emptyMap(),
    /** Time heard per episode under the current filter. */
    val heardMsByEpisode: Map<String, Long> = emptyMap(),
    /** Episodes with listening under the current filter, most recent first. */
    val episodeIds: List<String> = emptyList(),
)

/**
 * Aggregates the recorded listening segments into headline stats. Re-listens count
 * again — this measures time spent listening, not unique content covered.
 *
 * Pure Kotlin (no Android deps) so it is covered by JVM unit tests.
 */
object ListeningStatsCalculator {

    fun compute(
        history: List<EpisodeHistorySummary>,
        segments: List<ListeningSegmentEntity>,
        nowMs: Long,
        filter: ProfileFilter = ProfileFilter.All,
    ): ListeningStats {
        val week = nowMs - 7L * MILLIS_PER_DAY
        val month = nowMs - 30L * MILLIS_PER_DAY
        // Always over every segment: the filter chips must keep showing their own
        // totals even while one of them is selected.
        val msByProfile = segments.groupBy { it.profileId }
            .mapValues { (_, rows) -> rows.sumOf { heardMs(it) } }

        val selected = when (filter) {
            ProfileFilter.All -> segments
            is ProfileFilter.Only -> segments.filter { it.profileId == filter.profileId }
        }
        var total = 0L
        var last7 = 0L
        var last30 = 0L
        val heardByEpisode = mutableMapOf<String, Long>()
        val lastHeardByEpisode = mutableMapOf<String, Long>()
        for (s in selected) {
            val heard = heardMs(s)
            total += heard
            if (s.endedAt >= week) last7 += heard
            if (s.endedAt >= month) last30 += heard
            heardByEpisode[s.episodeId] = (heardByEpisode[s.episodeId] ?: 0) + heard
            lastHeardByEpisode[s.episodeId] =
                maxOf(lastHeardByEpisode[s.episodeId] ?: 0, s.endedAt)
        }
        // Unfiltered, the DAO's per-episode SUM is authoritative and already ordered.
        // Under a filter it is not — it sums across every profile — so the same
        // figures are rebuilt from the filtered segments instead.
        val byId = history.associateBy { it.id }
        val filtered = filter != ProfileFilter.All
        val episodeIds = if (filtered) {
            lastHeardByEpisode.entries
                .sortedByDescending { it.value }
                .map { it.key }
                .filter { it in byId }
        } else {
            history.map { it.id }
        }
        val heardPerEpisode = if (filtered) {
            heardByEpisode
        } else {
            history.associate { it.id to it.totalListenedMs }
        }
        val byShow = episodeIds.groupBy { byId.getValue(it).podcastTitle }
            .map { (show, ids) -> ShowListening(show, ids.sumOf { heardPerEpisode[it] ?: 0 }) }
            .sortedByDescending { it.listenedMs }
        return ListeningStats(
            totalMs = total,
            last7DaysMs = last7,
            last30DaysMs = last30,
            episodesTouched = episodeIds.size,
            episodesCompleted = episodeIds.count { byId.getValue(it).completed },
            topShows = byShow,
            msByProfile = msByProfile,
            heardMsByEpisode = heardPerEpisode,
            episodeIds = episodeIds,
        )
    }

    private fun heardMs(s: ListeningSegmentEntity): Long =
        (s.endPositionMs - s.startPositionMs).coerceAtLeast(0)

    private const val MILLIS_PER_DAY = 86_400_000L
}
