package com.podly

import com.podly.network.RssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class RssParserTest {

    private val feedXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>Test Show</title>
            <description>A show about tests</description>
            <itunes:author>Test Author</itunes:author>
            <itunes:image href="https://example.com/show.jpg"/>
            <item>
              <title>Episode Two</title>
              <guid>ep-2</guid>
              <description>Second episode</description>
              <enclosure url="https://example.com/ep2.mp3" type="audio/mpeg" length="123"/>
              <pubDate>Tue, 02 Jan 2024 10:00:00 +0000</pubDate>
              <itunes:duration>01:02:03</itunes:duration>
            </item>
            <item>
              <title>Episode One</title>
              <guid>ep-1</guid>
              <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg"/>
              <pubDate>Mon, 01 Jan 2024 10:00:00 +0000</pubDate>
              <itunes:duration>45:30</itunes:duration>
            </item>
            <item>
              <title>No audio here</title>
              <guid>ep-0</guid>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `parses channel metadata`() {
        val feed = RssParser().parse(StringReader(feedXml))
        assertEquals("Test Show", feed.title)
        assertEquals("Test Author", feed.author)
        assertEquals("A show about tests", feed.description)
        assertEquals("https://example.com/show.jpg", feed.imageUrl)
    }

    @Test
    fun `parses episodes and skips items without enclosures`() {
        val feed = RssParser().parse(StringReader(feedXml))
        assertEquals(2, feed.episodes.size)

        val ep2 = feed.episodes[0]
        assertEquals("Episode Two", ep2.title)
        assertEquals("ep-2", ep2.guid)
        assertEquals("https://example.com/ep2.mp3", ep2.audioUrl)
        assertEquals("Second episode", ep2.description)
        assertEquals((1 * 3600 + 2 * 60 + 3) * 1000L, ep2.durationMs)
        assertTrue(ep2.pubDateMs!! > 0)

        val ep1 = feed.episodes[1]
        assertEquals((45 * 60 + 30) * 1000L, ep1.durationMs)
        assertTrue(ep2.pubDateMs > ep1.pubDateMs!!)
    }

    @Test
    fun `parses duration formats`() {
        assertEquals(90_000L, RssParser.parseDuration("90"))
        assertEquals(90_000L, RssParser.parseDuration("1:30"))
        assertEquals(3_661_000L, RssParser.parseDuration("1:01:01"))
        assertNull(RssParser.parseDuration("not a duration"))
        assertNull(RssParser.parseDuration(null))
    }

    @Test
    fun `parses rfc822 dates`() {
        assertTrue(RssParser.parseRfc822("Mon, 01 Jan 2024 10:00:00 +0000")!! > 0)
        assertTrue(RssParser.parseRfc822("Mon, 01 Jan 2024 10:00:00 GMT")!! > 0)
        assertNull(RssParser.parseRfc822("garbage"))
        assertNull(RssParser.parseRfc822(null))
    }
}
