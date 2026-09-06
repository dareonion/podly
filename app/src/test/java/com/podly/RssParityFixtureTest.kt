package com.podly

import com.podly.data.db.stableId
import com.podly.network.RssParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins how a feed becomes episodes, because the out-of-repo pool generator
 * (`tools/radio`) has to reproduce it exactly.
 *
 * The ids the generator publishes are `stableId(guid ?: audioUrl)`, so any
 * disagreement about which enclosure counts, or about the raw text of a guid,
 * silently produces a second row for an episode the app already has. The
 * fixture deliberately includes a relative guid (which a normalising parser
 * would resolve against the feed URL), an enclosure with no type attribute, an
 * item with two enclosures, a CJK title, and an undated item.
 */
class RssParityFixtureTest {

    @Serializable
    private data class Expected(
        val title: String,
        val guid: String? = null,
        val audioUrl: String,
        val pubDateMs: Long? = null,
        val durationMs: Long? = null,
        val id: String,
    )

    private fun resource(name: String) =
        javaClass.getResourceAsStream("/$name")!!.bufferedReader()

    @Test
    fun `the app parser matches the shared fixture`() {
        val expected = Json.decodeFromString<List<Expected>>(
            resource("parity-feed.expected.json").use { it.readText() },
        )
        val parsed = resource("parity-feed.xml").use { RssParser().parse(it) }

        // The item without an enclosure is not an episode at all.
        assertEquals(expected.size, parsed.episodes.size)
        parsed.episodes.forEachIndexed { index, episode ->
            val want = expected[index]
            assertEquals("title[$index]", want.title, episode.title)
            assertEquals("guid[$index]", want.guid, episode.guid)
            assertEquals("audioUrl[$index]", want.audioUrl, episode.audioUrl)
            assertEquals("durationMs[$index]", want.durationMs, episode.durationMs)
            assertEquals("pubDateMs[$index]", want.pubDateMs, episode.pubDateMs)
            assertEquals(
                "stableId[$index]",
                want.id,
                stableId(episode.guid ?: episode.audioUrl),
            )
        }
    }
}
