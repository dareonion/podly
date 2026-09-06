package com.podly

import com.podly.data.db.RadioCandidateRow
import com.podly.radio.RadioCandidate
import com.podly.radio.RadioProfiles
import com.podly.radio.RadioScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RadioScorerTest {

    private val now = 1_760_000_000_000L
    private val day = 86_400_000L

    private fun row(
        id: String,
        show: String = "show-$id",
        ageDays: Long = 1,
        durationMs: Long? = 30 * 60_000L,
        language: String? = null,
        priority: Float = 0f,
        skipCount: Int = 0,
        lastSkippedAt: Long = 0,
    ) = RadioCandidateRow(
        episodeId = id,
        podcastId = show,
        podcastTitle = show,
        title = "ep $id",
        durationMs = durationMs,
        pubDateMs = now - ageDays * day,
        playbackPositionMs = 0,
        lastPlayedAt = 0,
        userRating = null,
        language = language,
        priority = priority,
        reason = null,
        lastServedAt = 0,
        serveCount = 0,
        lastSkippedAt = lastSkippedAt,
        skipCount = skipCount,
    )

    private fun backlog(vararg rows: RadioCandidateRow) =
        rows.map { RadioCandidate(it, discovery = false) }

    @Test
    fun `newer episodes outrank older ones`() {
        val picked = RadioScorer.nextBatch(
            backlog = backlog(row("old", ageDays = 300), row("new", ageDays = 1)),
            discovery = emptyList(),
            profile = RadioProfiles.YOU.copy(weights = RadioProfiles.YOU.weights.copy(jitter = 0.0)),
            nowMs = now, exclude = emptySet(), count = 2, random = Random(7),
        )
        assertEquals(listOf("new", "old"), picked.map { it.episodeId })
    }

    @Test
    fun `a recent skip sinks an episode without removing it`() {
        val picked = RadioScorer.nextBatch(
            backlog = backlog(
                row("skipped", ageDays = 1, skipCount = 3, lastSkippedAt = now - day),
                row("clean", ageDays = 30),
            ),
            discovery = emptyList(),
            profile = RadioProfiles.YOU.copy(weights = RadioProfiles.YOU.weights.copy(jitter = 0.0)),
            nowMs = now, exclude = emptySet(), count = 2, random = Random(7),
        )
        assertEquals(listOf("clean", "skipped"), picked.map { it.episodeId })
    }

    @Test
    fun `the batch spreads across shows before repeating one`() {
        val picked = RadioScorer.nextBatch(
            backlog = backlog(
                row("a1", show = "A", ageDays = 1),
                row("a2", show = "A", ageDays = 2),
                row("b1", show = "B", ageDays = 40),
            ),
            discovery = emptyList(),
            profile = RadioProfiles.YOU.copy(weights = RadioProfiles.YOU.weights.copy(jitter = 0.0)),
            nowMs = now, exclude = emptySet(), count = 2, random = Random(7),
        )
        assertEquals(setOf("A", "B"), picked.map { it.podcastId }.toSet())
    }

    @Test
    fun `the duration cap and language filter are enforced`() {
        val toddler = RadioProfiles.TODDLER_ZH
        val picked = RadioScorer.nextBatch(
            backlog = backlog(
                row("long", durationMs = 60 * 60_000L),
                row("english", durationMs = 5 * 60_000L, language = "en"),
                row("ok", durationMs = 5 * 60_000L, language = "zh-tw"),
                row("unknown-language", durationMs = 5 * 60_000L),
            ),
            discovery = emptyList(),
            profile = toddler,
            nowMs = now, exclude = emptySet(), count = 5, random = Random(3),
        )
        val ids = picked.map { it.episodeId }
        assertTrue("$ids should keep the short Mandarin pick", "ok" in ids)
        assertTrue("$ids must drop the hour-long pick", "long" !in ids)
        assertTrue("$ids must drop the English pick", "english" !in ids)
        // A candidate that declares no language is not excluded by a language filter.
        assertTrue("$ids", "unknown-language" in ids)
    }

    @Test
    fun `excluded episodes are never returned`() {
        val picked = RadioScorer.nextBatch(
            backlog = backlog(row("a"), row("b")),
            discovery = emptyList(),
            profile = RadioProfiles.YOU,
            nowMs = now, exclude = setOf("a"), count = 5, random = Random(1),
        )
        assertEquals(listOf("b"), picked.map { it.episodeId })
    }

    @Test
    fun `asking for more than exists returns what there is`() {
        val picked = RadioScorer.nextBatch(
            backlog = backlog(row("a")), discovery = emptyList(),
            profile = RadioProfiles.YOU,
            nowMs = now, exclude = emptySet(), count = 5, random = Random(1),
        )
        assertEquals(1, picked.size)
    }
}
