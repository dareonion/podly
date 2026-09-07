package com.podly

import com.podly.network.ParsedTranscript
import com.podly.network.TranscriptParser
import com.podly.network.preferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The examples from the Podcasting 2.0 spec, verbatim, as the fixtures.
 *
 * podcast-namespace/docs/tags/transcript.md and docs/examples/transcripts/.
 * Written from the spec rather than from what my parser already did, which is
 * how the HTML structure and the speaker carry-forward turned up as gaps.
 */
class TranscriptSpecTest {

    @Test
    fun `webvtt names a speaker only when it changes`() {
        // The spec's own snippet: John, Tom, then an untagged card that is still
        // Tom, then John again.
        val cues = TranscriptParser.parse(
            """
            WEBVTT

            00:00:00.000 --> 00:00:05.000
            <v John>Podcasting 2.0 is really changing the game.

            00:00:05.000 --> 00:00:10.000
            <v Tom>Yeah, absolutely. The new features are incredible.

            00:00:10.000 --> 00:00:15.000
            It's amazing how it's empowering creators like never before.

            00:00:15.000 --> 00:00:20.000
            <v John>Exactly, Tom. It's revolutionizing the industry.
            """.trimIndent(),
            "text/vtt",
        )
        assertEquals(listOf("John", "Tom", "Tom", "John"), cues.map { it.speaker })
        assertEquals(10_000L, cues[2].startMs)
    }

    @Test
    fun `srt carries the speaker across continuation cards`() {
        // "Start a new card when the speaker changes" — so cards 2 and 3 are
        // still Sarah, and card 4 switches to Gillian.
        val cues = TranscriptParser.parse(
            """
            1
            00:00:00,000 --> 00:00:02,760
            Sarah: In today's episode,
            you'll learn whether or not you

            2
            00:00:02,760 --> 00:00:06,090
            should have a podcast trailer.

            3
            00:00:19,080 --> 00:00:21,450
            Gillian: Hi Buzzsprout, Gillian
            here from breaking through
            """.trimIndent(),
            "application/x-subrip",
        )
        assertEquals(listOf("Sarah", "Sarah", "Gillian"), cues.map { it.speaker })
        assertEquals(19_080L, cues[2].startMs)
    }

    @Test
    fun `the json form is word-by-word and must be merged to be readable`() {
        val cues = TranscriptParser.parse(
            """
            {"version":"1.0.0","segments":[
             {"speaker":"Darth Vader","startTime":0.5,"endTime":0.75,"body":"I"},
             {"speaker":"Darth Vader","startTime":1,"endTime":1.25,"body":"am"},
             {"speaker":"Darth Vader","startTime":1.5,"endTime":2.0,"body":"your"},
             {"speaker":"Darth Vader","startTime":2.25,"endTime":2.5,"body":"father.\n"},
             {"speaker":"Luke","startTime":2.75,"endTime":3.0,"body":"Nooooo"}
            ]}
            """.trimIndent(),
            "application/json",
        )
        assertEquals(2, cues.size)
        assertEquals("I am your father.", cues[0].text)
        assertEquals(500L, cues[0].startMs)   // startTime is in seconds
        assertEquals(2_500L, cues[0].endMs)
        assertEquals("Nooooo", cues[1].text)
    }

    @Test
    fun `html keeps its cite, time and p structure`() {
        val cues = TranscriptParser.parse(
            """
            <cite>Kevin:</cite>
            <time>0:00</time>
            <p>We have an update planned where we would like to give the ability
               to upload an artwork file for these videos</p>
            <cite>Alban :</cite>
            <time>0:09</time>
            <p>You're triggering Tom right now with a hey, here's a cool feature.</p>
            """.trimIndent(),
            "text/html",
        )
        assertEquals(2, cues.size)
        // The examples write both "Kevin:" and "Alban :".
        assertEquals(listOf("Kevin", "Alban"), cues.map { it.speaker })
        assertEquals(0L, cues[0].startMs)
        assertEquals(9_000L, cues[1].startMs)
        assertTrue(cues[0].text.startsWith("We have an update planned"))
    }

    @Test
    fun `html without the structure still reads as prose`() {
        val cues = TranscriptParser.parse("<p>Just a paragraph.</p>", "text/html")
        assertEquals("Just a paragraph.", cues.single().text)
    }

    @Test
    fun `the preferred transcript is the most useful one on offer`() {
        val all = listOf(
            ParsedTranscript("a.txt", "text/plain", null, null),
            ParsedTranscript("b.html", "text/html", null, null),
            ParsedTranscript("c.srt", "application/x-subrip", null, "captions"),
            ParsedTranscript("d.vtt", "text/vtt", null, null),
        )
        assertEquals("d.vtt", all.preferred("en")?.url)
        // The spec's canonical SRT type beats html and plain.
        assertEquals("c.srt", (all - all[3]).preferred("en")?.url)
    }

    @Test
    fun `a transcript in the feed's own language wins over a better format`() {
        val all = listOf(
            ParsedTranscript("es.json", "application/json", "es", null),
            ParsedTranscript("en.txt", "text/plain", "en", null),
        )
        assertEquals("en.txt", all.preferred("en")?.url)
        assertEquals("es.json", all.preferred("es")?.url)
        // Regional variants still match: pt-BR against a pt feed.
        assertEquals(
            "x", listOf(ParsedTranscript("x", "text/plain", "pt-BR", null)).preferred("pt")?.url,
        )
    }

    @Test
    fun `no language attribute means the feed's language, not a mismatch`() {
        val all = listOf(ParsedTranscript("u.vtt", "text/vtt", null, null))
        assertEquals("u.vtt", all.preferred("de")?.url)
        assertNull(emptyList<ParsedTranscript>().preferred("de"))
    }

    @Test
    fun `a full transcript is preferred to captions of the same format`() {
        val all = listOf(
            ParsedTranscript("caps.vtt", "text/vtt", null, "captions"),
            ParsedTranscript("full.vtt", "text/vtt", null, null),
        )
        assertEquals("full.vtt", all.preferred("en")?.url)
    }
}
