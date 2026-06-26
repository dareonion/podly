package com.podly

import com.podly.data.CachedAcclaimed
import com.podly.data.CachedRecentEpisodes
import com.podly.network.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the wire contract between the `:generator` module (which writes the static
 * files) and the app (which deserializes them). The fixtures mirror what the
 * generator's `Payloads.kt` emits; if the app's cache classes drift from that shape,
 * these tests fail.
 */
class RemoteRecsContractTest {

    private fun load(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }

    @Test
    fun `recent file deserializes with coverage and resolved picks`() {
        val file = Http.json.decodeFromString<CachedRecentEpisodes>(load("recent-month.json"))

        assertEquals(1, file.version)
        assertEquals("MONTH", file.window)
        assertEquals("2026-05-26", file.coverageStart)
        assertEquals("2026-06-26", file.coverageEnd)
        assertTrue(file.generatedAtMs > 0)
        // fetchedAtMs is app-only; absent from the wire file, so it defaults to 0.
        assertEquals(0L, file.fetchedAtMs)
        assertEquals(2, file.picks.size)

        val resolved = file.picks[0]
        assertEquals("A Big Story", resolved.pick.episodeTitle)
        assertEquals("2026-06-20", resolved.pick.publishedApprox)
        val podcast = resolved.toPodcastOrNull()
        assertNotNull(podcast)
        assertEquals("https://feeds.simplecast.com/thedaily", podcast!!.feedUrl)

        // A pick with no iTunes match carries no resolved podcast.
        assertNull(file.picks[1].toPodcastOrNull())
    }

    @Test
    fun `acclaimed file deserializes with coverage label`() {
        val file = Http.json.decodeFromString<CachedAcclaimed>(load("acclaimed.json"))

        assertEquals("the last 12 months", file.coverageLabel)
        assertTrue(file.generatedAtMs > 0)
        assertEquals(0L, file.fetchedAtMs)
        assertEquals(1, file.picks.size)

        val pick = file.picks[0]
        // Whole-podcast recommendation: episodeTitle omitted in the file.
        assertNull(pick.pick.episodeTitle)
        assertEquals("2025 Peabody Award winner.", pick.pick.accolade)
        assertNotNull(pick.toPodcastOrNull())
    }
}
