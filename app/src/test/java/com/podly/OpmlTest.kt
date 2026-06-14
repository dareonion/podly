package com.podly

import com.podly.data.OpmlExporter
import com.podly.data.OpmlParser
import com.podly.data.db.PodcastEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class OpmlTest {

    @Test
    fun `parses podcast outlines by xml url`() {
        val opml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0">
              <body>
                <outline text="Tech">
                  <outline type="rss" text="Example Show" xmlUrl="https://example.com/feed.xml"/>
                </outline>
                <outline type="rss" title="Other Show" xmlUrl="https://example.com/other.xml"/>
                <outline text="No feed"/>
              </body>
            </opml>
        """.trimIndent()

        val outlines = OpmlParser().parse(StringReader(opml))

        assertEquals(2, outlines.size)
        assertEquals("Example Show", outlines[0].title)
        assertEquals("https://example.com/feed.xml", outlines[0].feedUrl)
        assertEquals("Other Show", outlines[1].title)
    }

    @Test
    fun `exports subscriptions as escaped opml`() {
        val opml = OpmlExporter.export(
            listOf(
                PodcastEntity(
                    id = "1",
                    title = "A & B \"Show\"",
                    author = "",
                    feedUrl = "https://example.com/feed?a=1&b=2",
                    artworkUrl = null,
                    description = null,
                    subscribed = true,
                )
            )
        )

        assertTrue(opml.contains("""<opml version="2.0">"""))
        assertTrue(opml.contains("A &amp; B &quot;Show&quot;"))
        assertTrue(opml.contains("https://example.com/feed?a=1&amp;b=2"))
    }
}
