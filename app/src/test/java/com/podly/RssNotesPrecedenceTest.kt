package com.podly

import com.podly.network.RssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

/**
 * Which of an item's three description tags becomes the show notes.
 *
 * They are not interchangeable, and the parser used to take whichever came
 * first: on Megaphone that meant storing <description> and losing 2,200
 * characters of <content:encoded>. On The Daily, <itunes:summary> is a quarter
 * the length of the other two, so preferring it would be just as wrong.
 */
class RssNotesPrecedenceTest {

    private fun parse(item: String) = RssParser().parse(
        StringReader(
            """<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
                 xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Show</title>
                <description>Channel blurb, not an episode's</description>
                <item>
                  <title>An episode</title>
                  <guid>ep-1</guid>
                  $item
                  <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg"/>
                  <pubDate>Tue, 02 Jan 2024 10:00:00 +0000</pubDate>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        ),
    ).episodes.single()

    @Test
    fun `content encoded wins even when it comes last`() {
        // Megaphone's order: description, itunes:summary, content:encoded.
        val ep = parse(
            """<description>short</description>
               <itunes:summary>short</itunes:summary>
               <content:encoded>the full notes, with links</content:encoded>""",
        )
        assertEquals("the full notes, with links", ep.description)
    }

    @Test
    fun `content encoded wins even when it comes first`() {
        val ep = parse(
            """<content:encoded>the full notes</content:encoded>
               <description>short</description>""",
        )
        assertEquals("the full notes", ep.description)
    }

    @Test
    fun `description beats the summary when there are no full notes`() {
        // The Daily's itunes:summary is a quarter the length of its description.
        val ep = parse(
            """<itunes:summary>a quarter of it</itunes:summary>
               <description>all of it</description>""",
        )
        assertEquals("all of it", ep.description)
    }

    @Test
    fun `the summary is used when it is all there is`() {
        assertEquals("only this", parse("<itunes:summary>only this</itunes:summary>").description)
    }

    @Test
    fun `an item with no notes does not inherit the channel's`() {
        assertNull(parse("").description)
    }

    @Test
    fun `notes do not leak between items`() {
        val feed = RssParser().parse(
            StringReader(
                """<?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
                  <channel>
                    <item>
                      <title>One</title><guid>a</guid>
                      <content:encoded>first notes</content:encoded>
                      <enclosure url="https://e/1.mp3" type="audio/mpeg"/>
                      <pubDate>Tue, 02 Jan 2024 10:00:00 +0000</pubDate>
                    </item>
                    <item>
                      <title>Two</title><guid>b</guid>
                      <enclosure url="https://e/2.mp3" type="audio/mpeg"/>
                      <pubDate>Wed, 03 Jan 2024 10:00:00 +0000</pubDate>
                    </item>
                  </channel>
                </rss>
                """.trimIndent(),
            ),
        )
        assertEquals("first notes", feed.episodes[0].description)
        assertNull(feed.episodes[1].description)
    }
}
