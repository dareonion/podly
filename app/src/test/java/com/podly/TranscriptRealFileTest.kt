package com.podly

import com.podly.network.TranscriptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser against a real published transcript rather than a fixture I wrote
 * to suit it: the opening of Darren's own feed, which is where this feature
 * actually gets used. It caught the full-width colon.
 */
class TranscriptRealFileTest {

    private val body: String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("transcript-plain-zh.txt"))
            .bufferedReader().readText()

    @Test
    fun `it reads as attributed dialogue, not one wall of text`() {
        val cues = TranscriptParser.parse(body, "text/plain")
        assertTrue("expected many cues, got ${cues.size}", cues.size > 10)
        val attributed = cues.count { it.speaker != null }
        assertTrue("most lines should have a speaker, got $attributed of ${cues.size}",
            attributed > cues.size / 2)
        assertTrue(cues.any { it.speaker == "小陳" })
        assertTrue(cues.any { it.speaker == "雲哲" })
    }

    @Test
    fun `no cue is left holding markdown furniture`() {
        val cues = TranscriptParser.parse(body, "text/plain")
        assertTrue(cues.none { it.text.contains("====") })
        assertTrue(cues.none { it.text.startsWith("#") })
        assertTrue(cues.none { it.text.isBlank() })
    }

    @Test
    fun `a speaker's name never swallows their line`() {
        val cues = TranscriptParser.parse(body, "text/plain")
        cues.filter { it.speaker != null }.forEach { cue ->
            assertTrue("empty body after '${cue.speaker}'", cue.text.isNotBlank())
            assertTrue("speaker too long: ${cue.speaker}", cue.speaker!!.length <= 24)
        }
        // Untimed source, so nothing is seekable and the UI must not assume it is.
        assertEquals(0, cues.count { it.startMs != null })
    }
}
