package com.podly.generator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RecsClientTest {
    @Test
    fun `parses a clean recent-episode array`() {
        val raw = """
            [{"podcastTitle":"Show A","episodeTitle":"Ep 1","author":"Host","reason":"Great.","publishedApprox":"2026-06-20"}]
        """.trimIndent()
        val picks = RecsClient.parseArray<RecentEpisodePick>(raw)
        assertEquals(1, picks.size)
        assertEquals("Ep 1", picks[0].episodeTitle)
        assertEquals("2026-06-20", picks[0].publishedApprox)
    }

    @Test
    fun `tolerates code fences and prose around the array`() {
        val raw = """
            Here are my picks:
            ```json
            [{"podcastTitle":"Show B","episodeTitle":"Ep 2","reason":"Funny."}]
            ```
        """.trimIndent()
        val picks = RecsClient.parseArray<RecentEpisodePick>(raw)
        assertEquals("Show B", picks[0].podcastTitle)
        assertTrue(picks[0].author == null)
    }

    @Test
    fun `parses acclaimed items with null episodeTitle`() {
        val raw = """[{"podcastTitle":"Show C","episodeTitle":null,"author":"Net","accolade":"Peabody 2025 winner."}]"""
        val items = RecsClient.parseArray<AcclaimedItem>(raw)
        assertEquals(1, items.size)
        assertTrue(items[0].episodeTitle == null)
    }

    @Test
    fun `throws when there is no JSON array`() {
        assertThrows(IOException::class.java) {
            RecsClient.parseArray<RecentEpisodePick>("no array here")
        }
    }
}
