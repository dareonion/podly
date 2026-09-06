package com.podly

import com.podly.playback.RADIO_LOOKAHEAD
import com.podly.playback.RADIO_LOOKBACK
import com.podly.playback.RADIO_MIN_LOOKAHEAD
import com.podly.playback.radioQueuePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue is kept deliberately shallow, and trimming played items is what keeps a
 * refilling radio session far below the Cast queue cap.
 */
class RadioQueuePlanTest {

    @Test
    fun `an empty queue asks for a full lookahead`() {
        assertEquals(RADIO_LOOKAHEAD, radioQueuePlan(itemCount = 0, currentIndex = 0).fetchCount)
    }

    @Test
    fun `refill fires at the threshold and not above it`() {
        // 3 ahead: deep enough.
        assertEquals(0, radioQueuePlan(itemCount = 5, currentIndex = 1).fetchCount)
        // Exactly 2 ahead: refill.
        assertEquals(RADIO_LOOKAHEAD - RADIO_MIN_LOOKAHEAD, radioQueuePlan(5, 2).fetchCount)
        // Nothing ahead: ask for the whole lookahead.
        assertEquals(RADIO_LOOKAHEAD, radioQueuePlan(5, 4).fetchCount)
    }

    @Test
    fun `trimming never touches the current item`() {
        assertNull(radioQueuePlan(itemCount = 6, currentIndex = 5).trimRange)
        val plan = radioQueuePlan(itemCount = 12, currentIndex = 8)
        val trim = requireNotNull(plan.trimRange)
        assertEquals(0, trim.first)
        assertTrue("trim=$trim must stay behind the current index", trim.last < 8)
        assertEquals(8 - RADIO_LOOKBACK - 1, trim.last)
    }

    @Test
    fun `the steady state stays far below the cast queue cap`() {
        // Worst case: full lookback behind, full lookahead ahead, plus the current item.
        val steadyState = RADIO_LOOKBACK + 1 + RADIO_LOOKAHEAD
        assertTrue("steady state $steadyState", steadyState < 50)
    }

    @Test
    fun `an out of range index is clamped rather than throwing`() {
        assertEquals(RADIO_LOOKAHEAD, radioQueuePlan(itemCount = 3, currentIndex = 99).fetchCount)
        assertNull(radioQueuePlan(itemCount = 3, currentIndex = -5).trimRange)
    }
}
