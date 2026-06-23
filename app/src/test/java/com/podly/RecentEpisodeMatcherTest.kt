package com.podly

import com.podly.network.ai.RecentEpisodeMatcher
import com.podly.network.ai.RecentEpisodeMatcher.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Cases drawn from real cached recommendations vs. the actual feeds, where the AI's
 * episode title is a paraphrase rather than the verbatim RSS title.
 */
class RecentEpisodeMatcherTest {

    private fun dayMs(iso: String): Long =
        LocalDate.parse(iso).toEpochDay() * 86_400_000L

    @Test
    fun `exact title matches`() {
        val candidates = listOf(
            Candidate("Some Other Episode", dayMs("2026-06-01")),
            Candidate("Interview With \"Game Changer\" Host & Dropout CEO Sam Reich", dayMs("2026-06-22")),
        )
        val idx = RecentEpisodeMatcher.bestMatch(
            "Interview With \"Game Changer\" Host & Dropout CEO Sam Reich",
            "June 22, 2026",
            candidates,
        )
        assertEquals(1, idx)
    }

    @Test
    fun `paraphrased title matches on token overlap`() {
        // AI: "Community College as a Career-Change Hack" -> feed: "Community colleges are kind of underrated"
        val candidates = listOf(
            Candidate("The SpaceX IPO drama explained", dayMs("2026-06-20")),
            Candidate("Community colleges are kind of underrated", dayMs("2026-06-15")),
            Candidate("Should we tax AI?", dayMs("2026-06-10")),
        )
        val idx = RecentEpisodeMatcher.bestMatch(
            "Community College as a Career-Change Hack (June 15, 2026)",
            "2026-06-15",
            candidates,
        )
        assertEquals(1, idx)
    }

    @Test
    fun `weak title overlap still matches via same-day publish date`() {
        // AI: "Trump and the Save America Act Voter ID Push" -> feed: "What's Trump's beef with Senate Republicans?"
        val candidates = listOf(
            Candidate("Iran \"deal\": winners, losers, and regional impact", dayMs("2026-06-23")),
            Candidate("What's Trump's beef with Senate Republicans?", dayMs("2026-06-22")),
            Candidate("These swing voters are sour on Trump", dayMs("2026-06-20")),
        )
        val idx = RecentEpisodeMatcher.bestMatch(
            "Trump and the Save America Act Voter ID Push (June 22, 2026)",
            "June 22, 2026",
            candidates,
        )
        assertEquals(1, idx)
    }

    @Test
    fun `unrelated episodes far from the date are rejected`() {
        val candidates = listOf(
            Candidate("A totally different show topic about gardening", dayMs("2026-01-01")),
            Candidate("Another unrelated cooking episode", dayMs("2026-02-01")),
        )
        val idx = RecentEpisodeMatcher.bestMatch(
            "Does Forward Guidance Help or Hurt the Economy?",
            "2026-06-18",
            candidates,
        )
        assertNull(idx)
    }

    @Test
    fun `empty candidate list returns null`() {
        assertNull(RecentEpisodeMatcher.bestMatch("Anything", "2026-06-18", emptyList()))
    }
}
