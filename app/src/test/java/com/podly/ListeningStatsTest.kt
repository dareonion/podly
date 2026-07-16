package com.podly

import com.podly.data.db.EpisodeHistorySummary
import com.podly.data.db.ListeningSegmentEntity
import com.podly.ui.history.ListeningStatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningStatsTest {

    private val now = 100L * DAY

    private fun segment(episodeId: String, heardMs: Long, endedDaysAgo: Long) =
        ListeningSegmentEntity(
            episodeId = episodeId,
            startPositionMs = 0,
            endPositionMs = heardMs,
            startedAt = now - endedDaysAgo * DAY - heardMs,
            endedAt = now - endedDaysAgo * DAY,
        )

    private fun summary(id: String, show: String, listenedMs: Long, completed: Boolean = false) =
        EpisodeHistorySummary(
            id = id,
            podcastTitle = show,
            title = "ep $id",
            artworkUrl = null,
            durationMs = null,
            completed = completed,
            userNote = null,
            userRating = null,
            segmentCount = 1,
            firstListenedAt = 0,
            lastListenedAt = 0,
            totalListenedMs = listenedMs,
        )

    @Test
    fun `windows and totals count segment time by endedAt`() {
        val stats = ListeningStatsCalculator.compute(
            history = emptyList(),
            segments = listOf(
                segment("a", heardMs = 10 * MIN, endedDaysAgo = 1),
                segment("a", heardMs = 20 * MIN, endedDaysAgo = 10),
                segment("b", heardMs = 40 * MIN, endedDaysAgo = 40),
            ),
            nowMs = now,
        )
        assertEquals(70 * MIN, stats.totalMs)
        assertEquals(10 * MIN, stats.last7DaysMs)
        assertEquals(30 * MIN, stats.last30DaysMs)
    }

    @Test
    fun `top shows aggregate per-episode totals and sort descending`() {
        val stats = ListeningStatsCalculator.compute(
            history = listOf(
                summary("a", "Show One", 30 * MIN, completed = true),
                summary("b", "Show Two", 90 * MIN),
                summary("c", "Show One", 15 * MIN),
            ),
            segments = emptyList(),
            nowMs = now,
        )
        assertEquals(3, stats.episodesTouched)
        assertEquals(1, stats.episodesCompleted)
        assertEquals(listOf("Show Two", "Show One"), stats.topShows.map { it.podcastTitle })
        assertEquals(45 * MIN, stats.topShows[1].listenedMs)
    }

    private companion object {
        const val MIN = 60_000L
        const val DAY = 86_400_000L
    }
}
