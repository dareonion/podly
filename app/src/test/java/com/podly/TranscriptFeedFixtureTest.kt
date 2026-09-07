package com.podly

import com.podly.data.db.PodcastEntity
import com.podly.network.RssParser
import com.podly.network.TranscriptParser
import com.podly.network.preferred
import com.podly.network.toEpisodeEntities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * End to end on a real published feed and a real transcript file, trimmed from
 * Darren's own podcast — the one this feature actually reads.
 *
 * Fixtures rather than invented XML because the invented kind agreed with my
 * parser and the real kind did not: the full-width colon and the single-newline
 * turn separation were both found this way.
 */
class TranscriptFeedFixtureTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }
            .bufferedReader().readText()

    private val feed = RssParser().parse(StringReader(resource("transcript-feed.xml")))

    private val podcast = PodcastEntity(
        id = "p", title = "Hayden", author = "", feedUrl = "https://example/f",
        artworkUrl = null, description = null,
    )

    @Test
    fun `the channel language is read, since a transcript may not declare one`() {
        assertEquals("zh-TW", feed.language)
    }

    @Test
    fun `an episode offering vtt and plain text stores the vtt`() {
        val withBoth = feed.episodes.first { it.transcripts.size > 1 }
        assertEquals(
            setOf("text/vtt", "text/plain"),
            withBoth.transcripts.mapNotNull { it.type }.toSet(),
        )
        // Only the timed one can be tapped to seek, so it wins.
        assertEquals("text/vtt", withBoth.transcripts.preferred(feed.language)?.type)
    }

    @Test
    fun `the chosen transcript reaches the episode row`() {
        val rows = feed.toEpisodeEntities(podcast)
        val timed = rows.first { it.transcriptType == "text/vtt" }
        assertTrue(timed.transcriptUrl!!.endsWith(".vtt"))
        // The plain-only episode still gets its transcript, just an untimed one.
        val plain = rows.first { it.transcriptType == "text/plain" }
        assertTrue(plain.transcriptUrl!!.endsWith(".txt"))
        assertTrue(rows.all { it.transcriptUrl != null })
    }

    @Test
    fun `the real vtt parses into attributed, seekable lines`() {
        val cues = TranscriptParser.parse(resource("transcript-zh.vtt"), "text/vtt")
        assertTrue("expected several cues, got ${cues.size}", cues.size >= 3)
        // A numeric cue identifier sits above the timing line and is not speech.
        assertTrue(cues.none { it.text.matches(Regex("\\d+")) })
        // <v 小陳> is a speaker tag, not words.
        assertEquals("小陳", cues[0].speaker)
        assertEquals("大家好，我是小陳。", cues[0].text)
        assertEquals("雲哲", cues[1].speaker)
        assertEquals(0L, cues[0].startMs)
        assertEquals(2_908L, cues[0].endMs)
        assertEquals(3_408L, cues[1].startMs)
        assertTrue(cues.none { it.text.contains("<v") })
    }

    @Test
    fun `a wrapped Chinese cue rejoins without a space`() {
        // Caption files wrap for display. Chinese does not put spaces between
        // characters, so joining every wrapped line with one drops a space into
        // the middle of a sentence.
        val cues = TranscriptParser.parse(resource("transcript-wrapped-zh.vtt"), "text/vtt")
        val zh = cues.first { it.speaker == "雲哲" }
        assertEquals(
            "後來他玩木頭火車組：坐在地毯上，箱子裡裝著木頭軌道，手裡拿著一台紅色卡車和一台黃色小車車。",
            zh.text,
        )
    }

    @Test
    fun `a wrapped English cue keeps its space`() {
        val cues = TranscriptParser.parse(resource("transcript-wrapped-zh.vtt"), "text/vtt")
        val en = cues.first { it.text.startsWith("to put on") }
        assertEquals("to put on or wear (glasses, hat, gloves,…", en.text)
        // A bilingual feed uses a speaker for the gloss track.
        assertEquals("EN", en.speaker)
    }

    @Test
    fun `every cue in the real file is seekable`() {
        val cues = TranscriptParser.parse(resource("transcript-zh.vtt"), "text/vtt")
        assertTrue(cues.all { it.startMs != null })
        assertNull(cues.firstOrNull { it.text.isBlank() })
        assertNotNull(cues.last().endMs)
    }
}
