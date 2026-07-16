package com.podly.ui.history

import com.podly.data.db.EpisodeHistorySummary
import com.podly.data.db.ListeningSegmentEntity

data class ShowListening(val podcastTitle: String, val listenedMs: Long)

data class ListeningStats(
    val totalMs: Long,
    val last7DaysMs: Long,
    val last30DaysMs: Long,
    val episodesTouched: Int,
    val episodesCompleted: Int,
    /** Shows by total time listened, descending. */
    val topShows: List<ShowListening>,
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
    ): ListeningStats {
        val week = nowMs - 7L * MILLIS_PER_DAY
        val month = nowMs - 30L * MILLIS_PER_DAY
        var total = 0L
        var last7 = 0L
        var last30 = 0L
        for (s in segments) {
            val heard = (s.endPositionMs - s.startPositionMs).coerceAtLeast(0)
            total += heard
            if (s.endedAt >= week) last7 += heard
            if (s.endedAt >= month) last30 += heard
        }
        val byShow = history.groupBy { it.podcastTitle }
            .map { (show, episodes) -> ShowListening(show, episodes.sumOf { it.totalListenedMs }) }
            .sortedByDescending { it.listenedMs }
        return ListeningStats(
            totalMs = total,
            last7DaysMs = last7,
            last30DaysMs = last30,
            episodesTouched = history.size,
            episodesCompleted = history.count { it.completed },
            topShows = byShow,
        )
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
