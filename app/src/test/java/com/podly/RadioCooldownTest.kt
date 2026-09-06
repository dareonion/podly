package com.podly

import com.podly.radio.RadioCooldown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A skip is "not now", so every block must expire and every penalty must decay. */
class RadioCooldownTest {

    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test
    fun `no skips means no block and no penalty`() {
        assertEquals(0L, RadioCooldown.blockedUntil(0, now))
        assertEquals(0.0, RadioCooldown.softPenalty(0, 0, now), 1e-9)
    }

    @Test
    fun `blocks escalate and then cap`() {
        assertEquals(now + 6 * hour, RadioCooldown.blockedUntil(1, now))
        assertEquals(now + 2 * day, RadioCooldown.blockedUntil(2, now))
        assertEquals(now + 7 * day, RadioCooldown.blockedUntil(3, now))
        assertEquals(now + 30 * day, RadioCooldown.blockedUntil(4, now))
        assertEquals(now + 90 * day, RadioCooldown.blockedUntil(5, now))
        // Capped: a much-skipped episode still comes back.
        assertEquals(now + 90 * day, RadioCooldown.blockedUntil(9, now))
    }

    @Test
    fun `old skips stop counting so they cannot compound forever`() {
        assertEquals(3, RadioCooldown.effectiveSkipCount(3, now - 10 * day, now))
        assertEquals(0, RadioCooldown.effectiveSkipCount(3, now - 200 * day, now))
        assertEquals(0, RadioCooldown.effectiveSkipCount(3, 0, now))
    }

    @Test
    fun `the soft penalty decays toward zero`() {
        val fresh = RadioCooldown.softPenalty(2, now, now)
        val week = RadioCooldown.softPenalty(2, now - 7 * day, now)
        val ages = RadioCooldown.softPenalty(2, now - 60 * day, now)
        assertTrue("fresh=$fresh", fresh > 0.3)
        assertTrue("week=$week fresh=$fresh", week < fresh)
        assertTrue("ages=$ages", ages < 0.1)
        assertTrue(RadioCooldown.softPenalty(1, now, now) <= 1.0)
    }

    @Test
    fun `a decayed skip carries no penalty at all`() {
        assertEquals(0.0, RadioCooldown.softPenalty(4, now - 300 * day, now), 1e-9)
    }
}
