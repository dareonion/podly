package com.podly

import com.podly.network.TranscriptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five types `<podcast:transcript>` allows are not equally useful, and real
 * publishers ship the least useful one: Acquired's are 217 KB of plain,
 * speaker-prefixed prose with no timings at all. Everything must stay readable.
 */
class TranscriptParserTest {

    @Test
    fun `webvtt cues carry timings and speaker tags`() {
        val cues = TranscriptParser.parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:04.500
            <v Ben>I was telling my wife.

            00:00:04.500 --> 00:00:06.000
            Let's not go crazy here.
            """.trimIndent(),
            "text/vtt",
        )
        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(4_500L, cues[0].endMs)
        assertEquals("Ben", cues[0].speaker)
        assertEquals("I was telling my wife.", cues[0].text)
        // The spec names a speaker only when it changes, so an untagged cue
        // continues the previous one rather than being unattributed.
        assertEquals("Ben", cues[1].speaker)
    }

    @Test
    fun `srt uses a decimal comma and a cue number`() {
        val cues = TranscriptParser.parse(
            """
            1
            00:01:02,250 --> 00:01:04,000
            First line

            2
            00:01:04,000 --> 00:01:06,000
            Second line
            """.trimIndent(),
            "application/srt",
        )
        assertEquals(2, cues.size)
        assertEquals(62_250L, cues[0].startMs)
        assertEquals("First line", cues[0].text)
    }

    @Test
    fun `a mislabelled file is sniffed rather than trusted`() {
        // Publishers mislabel. A VTT served as text/plain would otherwise render
        // its own timestamps as prose.
        val cues = TranscriptParser.parse(
            "WEBVTT\n\n00:00:02.000 --> 00:00:03.000\nHello",
            "text/plain",
        )
        assertEquals(2_000L, cues.single().startMs)
        assertEquals("Hello", cues.single().text)
    }

    @Test
    fun `the podcasting 2 point 0 json form merges a speaker's run of segments`() {
        // Whisper-style output puts a few words in each segment, which is
        // unreadable one line at a time.
        val cues = TranscriptParser.parse(
            """
            {"version":"1.0.0","segments":[
              {"speaker":"Ben","startTime":1.0,"endTime":2.0,"body":"I was"},
              {"speaker":"Ben","startTime":2.0,"endTime":3.5,"body":"telling my wife."},
              {"speaker":"David","startTime":3.5,"endTime":5.0,"body":"Let's not."}
            ]}
            """.trimIndent(),
            "application/json",
        )
        assertEquals(2, cues.size)
        assertEquals("I was telling my wife.", cues[0].text)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_500L, cues[0].endMs)
        assertEquals("David", cues[1].speaker)
    }

    @Test
    fun `plain text keeps paragraphs and lifts the speaker out`() {
        // The shape Acquired actually publishes.
        val cues = TranscriptParser.parse(
            "Ben: I was telling my wife, you know.\n\nDavid: Let's not go crazy here.",
            "text/plain",
        )
        assertEquals(2, cues.size)
        assertEquals("Ben", cues[0].speaker)
        assertEquals("I was telling my wife, you know.", cues[0].text)
        assertNull(cues[0].startMs) // nothing to seek to, and the UI must cope
    }

    @Test
    fun `a colon mid-sentence is not a speaker`() {
        val cue = TranscriptParser.parse(
            "And so the question was this: how do index funds actually make money?",
            "text/plain",
        ).single()
        assertNull(cue.speaker)
        assertTrue(cue.text.startsWith("And so the question"))
    }

    @Test
    fun `html is reduced to readable paragraphs`() {
        val cues = TranscriptParser.parse(
            "<p>Ben: First thing.</p><p>David: Second thing.</p>",
            "text/html",
        )
        assertEquals(listOf("Ben", "David"), cues.map { it.speaker })
    }

    @Test
    fun `malformed json falls back to reading it as text rather than failing`() {
        val cues = TranscriptParser.parse("""{"segments": [ broken """, "application/json")
        assertTrue(cues.isNotEmpty())
    }

    @Test
    fun `a Chinese transcript's full-width colon marks a speaker`() {
        // Darren's own feed writes them this way, with no space after the colon.
        // An ASCII-only rule read every line as unattributed prose.
        val cues = TranscriptParser.parse(
            "小陳：大家好，歡迎收聽，我是小陳。\n\n雲哲：我是雲哲。",
            "text/plain",
        )
        assertEquals(listOf("小陳", "雲哲"), cues.map { it.speaker })
        assertEquals("大家好，歡迎收聽，我是小陳。", cues[0].text)
    }

    @Test
    fun `markdown furniture in a generated transcript is not read aloud`() {
        val cues = TranscriptParser.parse(
            """
            幼兒園的一天
            ============================================

            ## 開場

            小陳：大家好。
            """.trimIndent(),
            "text/plain",
        )
        assertTrue(cues.none { it.text.contains("====") })
        assertTrue(cues.any { it.text == "開場" })
        assertEquals("小陳", cues.last().speaker)
    }

    @Test
    fun `an empty file yields nothing`() {
        assertTrue(TranscriptParser.parse("   \n  ", "text/plain").isEmpty())
    }
}
