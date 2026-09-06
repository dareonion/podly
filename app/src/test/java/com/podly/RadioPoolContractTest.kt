package com.podly

import com.podly.data.db.stableId
import com.podly.data.radio.RadioPoolFile
import com.podly.network.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the wire contract with `tools/radio`, which generates these pools
 * outside this repo and in another language.
 *
 * The fixture is a trimmed copy of a real emission, not something hand-written,
 * so a field rename in the generator fails here rather than silently producing
 * an empty pool on the phone.
 */
class RadioPoolContractTest {

    private fun fixture(): RadioPoolFile {
        val json = javaClass.getResourceAsStream("/radio-pool-toddler.json")!!
            .bufferedReader().use { it.readText() }
        return Http.json.decodeFromString(json)
    }

    @Test
    fun `a generated pool deserializes with everything needed to play`() {
        val pool = fixture()
        assertEquals("toddler_zh", pool.profileId)
        assertEquals(1, pool.version)
        assertTrue("generatedAtMs", pool.generatedAtMs > 0)
        assertTrue("entries", pool.entries.isNotEmpty())

        pool.entries.forEach { entry ->
            // Everything an EpisodeEntity needs, with no network call.
            assertTrue("audioUrl", entry.episode.audioUrl.startsWith("http"))
            assertTrue("pubDateMs", entry.episode.pubDateMs > 0)
            assertTrue("title", entry.episode.title.isNotBlank())
            // And everything a PodcastEntity needs.
            assertTrue("feedUrl", entry.podcast.feedUrl.startsWith("http"))
            assertTrue("podcast title", entry.podcast.title.isNotBlank())
            assertNotNull("language", entry.language)
        }
    }

    @Test
    fun `ids match what the app derives itself`() {
        // If these ever drift, hydrating a pool inserts a second row for an
        // episode the app already has, and nothing surfaces the duplicate.
        fixture().entries.forEach { entry ->
            assertEquals(
                "episode id for ${entry.episode.title}",
                stableId(entry.episode.guid ?: entry.episode.audioUrl),
                entry.episode.id,
            )
            assertEquals(
                "podcast id for ${entry.podcast.title}",
                stableId(entry.podcast.feedUrl),
                entry.podcast.id,
            )
            assertEquals("entry id mirrors the episode id", entry.episode.id, entry.id)
        }
    }
}
