package com.podly

import com.podly.data.TranscriptCueLookup
import com.podly.network.TranscriptCue
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptCueLookupTest {
    @Test
    fun `lookup returns containing cue and no cue before between or after`() {
        val lookup = TranscriptCueLookup(listOf(cue(1_000, 2_000), cue(3_000, 4_000), cue(4_000, 5_000)))
        assertEquals(-1, lookup.at(0))
        assertEquals(0, lookup.at(1_000))
        assertEquals(0, lookup.at(1_999))
        assertEquals(-1, lookup.at(2_000))
        assertEquals(-1, lookup.at(2_500))
        assertEquals(1, lookup.at(3_999))
        assertEquals(2, lookup.at(4_000))
        assertEquals(-1, lookup.at(5_000))
        assertEquals(-1, lookup.at(Long.MAX_VALUE))
        // Seeking backwards uses the same lookup.
        assertEquals(0, lookup.at(1_500))
    }

    @Test
    fun `untimed and empty transcripts have no active cue`() {
        assertEquals(-1, TranscriptCueLookup(emptyList()).at(0))
        assertEquals(-1, TranscriptCueLookup(listOf(cue(null, null))).at(1_000))
    }

    @Test
    fun `mixed and unordered timings preserve display indices and infer missing ends`() {
        val lookup = TranscriptCueLookup(listOf(cue(null, null), cue(3_000, 4_000), cue(1_000, null)))
        assertEquals(2, lookup.at(2_999))
        assertEquals(1, lookup.at(3_000))
        assertEquals(-1, lookup.at(4_000))
    }

    @Test
    fun `an earlier longer cue stays active after an overlapping short one ends`() {
        val lookup = TranscriptCueLookup(listOf(cue(0, 10_000), cue(2_000, 3_000)))
        assertEquals(1, lookup.at(2_500))
        assertEquals(0, lookup.at(4_000))
        assertEquals(-1, lookup.at(10_000))
    }

    @Test
    fun `a long cue stays active however many short cues it overlaps`() {
        val shorts = (1..20).map { cue(it * 1_000L, it * 1_000L + 500) }
        val lookup = TranscriptCueLookup(listOf(cue(0, 100_000)) + shorts)
        assertEquals(0, lookup.at(25_000))
        assertEquals(20, lookup.at(20_200))
        assertEquals(0, lookup.at(20_700))
    }

    private fun cue(start: Long?, end: Long?) = TranscriptCue(start, end, null, "Line")
}
