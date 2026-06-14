package com.podly

import com.podly.network.ai.AiRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class AiRecommenderParseTest {

    @Test
    fun `parses bare json array`() {
        val recs = AiRecommender.parseRecommendations(
            """[{"title": "Show A", "author": "Alice", "reason": "Because."}]"""
        )
        assertEquals(1, recs.size)
        assertEquals("Show A", recs[0].title)
        assertEquals("Alice", recs[0].author)
    }

    @Test
    fun `tolerates code fences and prose`() {
        val raw = """
            Here are my picks:
            ```json
            [{"title": "Show B", "reason": "Great pacing."}]
            ```
        """.trimIndent()
        val recs = AiRecommender.parseRecommendations(raw)
        assertEquals("Show B", recs[0].title)
        assertEquals(null, recs[0].author)
    }

    @Test
    fun `parses acclaimed picks with and without episode titles`() {
        val picks = AiRecommender.parseAcclaimed(
            """[
                {"podcastTitle": "Show C", "episodeTitle": null, "author": "Carol", "accolade": "Won a 2026 Ambie."},
                {"podcastTitle": "Show D", "episodeTitle": "The Big One", "author": "Dave", "accolade": "2025 Peabody nominee."}
            ]"""
        )
        assertEquals(2, picks.size)
        assertEquals(null, picks[0].episodeTitle)
        assertEquals("The Big One", picks[1].episodeTitle)
        assertEquals("Won a 2026 Ambie.", picks[0].accolade)
    }

    @Test
    fun `parses episode picks`() {
        val picks = AiRecommender.parseEpisodePicks(
            """[{"episodeTitle": "The Origin Story", "reason": "The classic entry point."}]"""
        )
        assertEquals(1, picks.size)
        assertEquals("The Origin Story", picks[0].episodeTitle)
        assertEquals("The classic entry point.", picks[0].reason)
    }

    @Test
    fun `parses recent episode picks`() {
        val picks = AiRecommender.parseRecentEpisodePicks(
            """[{
                "podcastTitle": "Show E",
                "episodeTitle": "A Timely Listen",
                "author": "Eve",
                "reason": "A sharp explanation of a current story.",
                "publishedApprox": "June 2026"
            }]"""
        )
        assertEquals(1, picks.size)
        assertEquals("Show E", picks[0].podcastTitle)
        assertEquals("A Timely Listen", picks[0].episodeTitle)
        assertEquals("June 2026", picks[0].publishedApprox)
    }

    @Test
    fun `fails clearly when no array present`() {
        assertThrows(IOException::class.java) {
            AiRecommender.parseRecommendations("Sorry, I cannot help with that.")
        }
    }
}
