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
    fun `fails clearly when no array present`() {
        assertThrows(IOException::class.java) {
            AiRecommender.parseRecommendations("Sorry, I cannot help with that.")
        }
    }
}
