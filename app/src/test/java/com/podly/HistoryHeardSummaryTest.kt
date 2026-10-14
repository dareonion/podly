package com.podly

import com.podly.data.db.EpisodeHistorySummary
import com.podly.data.db.ListeningSegmentEntity
import com.podly.ui.history.HeardSummary
import com.podly.ui.history.heardSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryHeardSummaryTest {

    private fun segment(endedAt: Long, profileId: String?) = ListeningSegmentEntity(
        episodeId = "a",
        startPositionMs = 0,
        endPositionMs = 60_000,
        startedAt = endedAt - 60_000,
        endedAt = endedAt,
        profileId = profileId,
    )

    // Heard three times: twice under toddler radio, last of all outside radio.
    private val summary = EpisodeHistorySummary(
        id = "a", podcastTitle = "Little Story", title = "ep a", artworkUrl = null,
        durationMs = null, completed = false, userNote = null, userRating = null,
        segmentCount = 3, firstListenedAt = 1_000, lastListenedAt = 9_000, totalListenedMs = 180_000,
    )
    private val toddlerOnly = listOf(segment(5_000, "toddler_zh"), segment(3_000, "toddler_zh"))

    @Test
    fun `a filtered card counts only the filter's ranges and its last listen`() {
        assertEquals(HeardSummary(ranges = 2, lastListenedAt = 5_000), heardSummary(summary, toddlerOnly))
    }

    @Test
    fun `an unfiltered card agrees with the all-profile summary`() {
        val all = toddlerOnly + segment(9_000, null)
        assertEquals(HeardSummary(ranges = 3, lastListenedAt = 9_000), heardSummary(summary, all))
    }

    @Test
    fun `no segments falls back to the summary`() {
        assertEquals(HeardSummary(ranges = 3, lastListenedAt = 9_000), heardSummary(summary, emptyList()))
    }
}
