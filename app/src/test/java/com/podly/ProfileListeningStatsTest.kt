package com.podly

import com.podly.data.db.EpisodeHistorySummary
import com.podly.data.db.ListeningSegmentEntity
import com.podly.ui.history.ListeningStatsCalculator
import com.podly.ui.history.ProfileFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Per-profile history. The trap guarded here: EpisodeHistorySummary.totalListenedMs is
 * a SQL SUM across every profile, so a filtered view must not use it.
 */
class ProfileListeningStatsTest {

    private val now = 100L * DAY

    private fun segment(episodeId: String, heardMs: Long, profileId: String?) =
        ListeningSegmentEntity(
            episodeId = episodeId,
            startPositionMs = 0,
            endPositionMs = heardMs,
            startedAt = now - heardMs,
            endedAt = now - DAY,
            profileId = profileId,
        )

    private fun summary(id: String, show: String, listenedMs: Long) =
        EpisodeHistorySummary(
            id = id, podcastTitle = show, title = "ep $id", artworkUrl = null,
            durationMs = null, completed = false, userNote = null, userRating = null,
            segmentCount = 1, firstListenedAt = 0, lastListenedAt = 0,
            totalListenedMs = listenedMs,
        )

    private val history = listOf(
        summary("a", "Fresh Air", 30 * MIN),
        summary("b", "Little Story", 20 * MIN),
    )
    private val segments = listOf(
        segment("a", 30 * MIN, profileId = null),
        segment("b", 20 * MIN, profileId = "toddler_zh"),
    )

    @Test
    fun `unfiltered totals are unchanged`() {
        val stats = ListeningStatsCalculator.compute(history, segments, now)
        assertEquals(50 * MIN, stats.totalMs)
        assertEquals(2, stats.episodesTouched)
    }

    @Test
    fun `filtering to a profile counts only that profile's listening`() {
        val stats = ListeningStatsCalculator.compute(
            history, segments, now, ProfileFilter.Only("toddler_zh"),
        )
        assertEquals(20 * MIN, stats.totalMs)
        assertEquals(1, stats.episodesTouched)
        assertEquals(listOf("Little Story"), stats.topShows.map { it.podcastTitle })
        // Not the DAO's cross-profile total for episode b.
        assertEquals(20 * MIN, stats.heardMsByEpisode["b"])
        assertEquals(listOf("b"), stats.episodeIds)
    }

    @Test
    fun `untagged listening is its own bucket`() {
        val stats = ListeningStatsCalculator.compute(
            history, segments, now, ProfileFilter.Only(null),
        )
        assertEquals(30 * MIN, stats.totalMs)
        assertEquals(listOf("a"), stats.episodeIds)
    }

    @Test
    fun `profile totals stay whole while a filter is applied`() {
        val filtered = ListeningStatsCalculator.compute(
            history, segments, now, ProfileFilter.Only("toddler_zh"),
        )
        assertEquals(30 * MIN, filtered.msByProfile[null])
        assertEquals(20 * MIN, filtered.msByProfile["toddler_zh"])
    }

    private companion object {
        const val MIN = 60_000L
        const val DAY = 86_400_000L
    }
}
