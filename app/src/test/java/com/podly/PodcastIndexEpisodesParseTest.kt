package com.podly

import com.podly.network.Http
import com.podly.network.PodcastIndexEpisodesResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Guards the fields PicksImporter's PodcastIndex rescue relies on. */
class PodcastIndexEpisodesParseTest {

    private val body = """
        {
          "status": "true",
          "items": [
            {
              "id": 42,
              "title": "Scott Pelley on His Firing",
              "guid": "e9e03398-f518-429e-9b8c-e6908c5decb8",
              "datePublished": 1780531200,
              "enclosureUrl": "https://nyt.simplecastaudio.com/x/y.mp3",
              "enclosureType": "audio/mpeg",
              "duration": 3600,
              "image": "",
              "description": "A conversation about the exit."
            },
            { "title": "No enclosure yet", "datePublished": 0 }
          ],
          "count": 2
        }
    """.trimIndent()

    @Test
    fun `decodes the episode fields the importer uses`() {
        val items = Http.json.decodeFromString<PodcastIndexEpisodesResponse>(body).items
        assertEquals(2, items.size)

        val ep = items[0]
        assertEquals("Scott Pelley on His Firing", ep.title)
        assertEquals("e9e03398-f518-429e-9b8c-e6908c5decb8", ep.guid)
        assertEquals(1780531200L, ep.datePublished)
        assertEquals("https://nyt.simplecastaudio.com/x/y.mp3", ep.enclosureUrl)
        assertEquals(3600L, ep.duration)
        assertEquals("", ep.image)

        assertNull(items[1].enclosureUrl)
        assertNull(items[1].guid)
    }
}
