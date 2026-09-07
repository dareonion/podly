package com.podly

import com.podly.data.db.ListeningSegmentEntity
import com.podly.playback.RadioProbation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Radio starts episodes nobody asked for, so a pick earns its place in Continue
 * listening and History rather than getting it on the first second of audio.
 */
class RadioProbationTest {

    private fun probation() = RadioProbation("ep", "you")

    private fun segment(episodeId: String, ms: Long) = ListeningSegmentEntity(
        episodeId = episodeId,
        startPositionMs = 0,
        endPositionMs = ms,
        startedAt = 0,
        endedAt = ms,
        profileId = "you",
    )

    @Test
    fun `a short listen commits nothing`() {
        val p = probation()
        p.buffer(segment("ep", 8_000))
        assertFalse(p.shouldCommit(liveMs = 0, ended = false))
        assertEquals(1, p.segments.size) // held back, not written
    }

    @Test
    fun `ninety seconds of listening commits`() {
        val p = probation()
        p.buffer(segment("ep", 45_000))
        assertFalse(p.shouldCommit(liveMs = 0, ended = false))
        p.buffer(segment("ep", 45_000))
        assertTrue(p.shouldCommit(liveMs = 0, ended = false))
    }

    @Test
    fun `the in-flight segment counts, so a continuous listen commits on time`() {
        // Without this a pick played straight through never reaches the threshold:
        // nothing is buffered until playback stops or the queue moves on.
        val p = probation()
        assertTrue(p.shouldCommit(liveMs = 90_000, ended = false))
        assertEquals(0, p.segments.size)
    }

    @Test
    fun `an episode that reached the end always commits`() {
        // A four-minute kids' story can finish without ever passing the threshold.
        assertTrue(probation().shouldCommit(liveMs = 1_000, ended = true))
    }

    @Test
    fun `segments for another episode are ignored`() {
        // A flush at a queue transition belongs to the episode that just ended,
        // and must not count toward the pick that just started.
        val p = probation()
        p.buffer(segment("other", 120_000))
        assertEquals(0, p.listenedMs)
        assertEquals(0, p.segments.size)
        assertFalse(p.shouldCommit(liveMs = 0, ended = false))
    }

    @Test
    fun `it knows which episode it covers`() {
        val p = probation()
        assertTrue(p.covers("ep"))
        assertFalse(p.covers("other"))
        assertFalse(p.covers(null))
        // The profile travels with it, so it can still commit after radio stops.
        assertEquals("you", p.profileId)
    }

    @Test
    fun `a negative or absent in-flight segment cannot subtract`() {
        val p = probation()
        p.buffer(segment("ep", 90_000))
        assertEquals(90_000, p.listenedIncluding(-5_000))
    }
}
