package com.podly

import com.podly.network.RssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * `<itunes:category>` is the signal a radio profile filters on, so what the
 * parser takes from it decides whether "no kids content" works at all.
 */
class RssParserCategoryTest {

    private fun parse(channelExtra: String) = RssParser().parse(
        StringReader(
            """<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Test Show</title>
                $channelExtra
                <item>
                  <title>An episode</title>
                  <guid>ep-1</guid>
                  <itunes:category text="Should Be Ignored"/>
                  <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg"/>
                  <pubDate>Tue, 02 Jan 2024 10:00:00 +0000</pubDate>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        ),
    )

    @Test
    fun `takes the parent category and its subcategory`() {
        val feed = parse(
            """<itunes:category text="Kids &amp; Family">
                 <itunes:category text="Stories for Kids"/>
               </itunes:category>""",
        )
        assertEquals(listOf("Kids & Family", "Stories for Kids"), feed.categories)
        assertEquals(1, feed.episodes.size)
    }

    @Test
    fun `keeps every top-level category and de-duplicates`() {
        val feed = parse(
            """<itunes:category text="True Crime"/>
               <itunes:category text="Society &amp; Culture">
                 <itunes:category text="Documentary"/>
               </itunes:category>
               <itunes:category text="True Crime"/>""",
        )
        assertEquals(
            listOf("True Crime", "Society & Culture", "Documentary"),
            feed.categories,
        )
    }

    @Test
    fun `an item-level category is not the show's`() {
        // Item categories describe one episode; treating them as the show's would
        // exclude a whole subscription on the strength of a single episode's tag.
        val feed = parse("")
        assertTrue(feed.categories.isEmpty())
    }

    @Test
    fun `a feed that declares nothing yields nothing rather than a blank`() {
        val feed = parse("""<itunes:category text="  "/>""")
        assertTrue(feed.categories.isEmpty())
    }
}
