package com.podly

import com.podly.playback.MAX_CAST_QUEUE_ITEMS
import com.podly.playback.castQueueWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the cap that keeps a Cast queue-load under the protocol's 512 KB
 * message ceiling. Sending a full library queue crashed the app with
 * "Message exceeds maximum size" on every press of play while casting.
 */
class CastQueueWindowTest {

    private fun queue(size: Int) = (0 until size).map { "ep$it" }

    @Test
    fun shortQueuesArePassedThroughUntouched() {
        val items = queue(10)
        val (windowed, index) = castQueueWindow(items, index = 3)
        assertSame(items, windowed)
        assertEquals(3, index)
    }

    @Test
    fun exactlyAtTheLimitIsStillUntouched() {
        val items = queue(MAX_CAST_QUEUE_ITEMS)
        val (windowed, _) = castQueueWindow(items, index = 0)
        assertEquals(MAX_CAST_QUEUE_ITEMS, windowed.size)
    }

    @Test
    fun longQueueIsCappedAndKeepsTheCurrentItem() {
        val items = queue(1315)
        val (windowed, index) = castQueueWindow(items, index = 900)
        assertEquals(MAX_CAST_QUEUE_ITEMS, windowed.size)
        // The returned index must still address the episode that was playing.
        assertEquals("ep900", windowed[index])
    }

    @Test
    fun windowKeepsSomeHistoryForPrevious() {
        val (windowed, index) = castQueueWindow(queue(1315), index = 900, lookback = 5)
        assertEquals(5, index)
        assertEquals("ep895", windowed.first())
    }

    @Test
    fun nearTheStartTheWindowDoesNotRunOffTheFront() {
        val (windowed, index) = castQueueWindow(queue(1315), index = 2)
        assertEquals("ep0", windowed.first())
        assertEquals(2, index)
    }

    @Test
    fun nearTheEndTheWindowDoesNotRunOffTheBack() {
        val items = queue(1315)
        val (windowed, index) = castQueueWindow(items, index = 1314)
        assertEquals(MAX_CAST_QUEUE_ITEMS, windowed.size)
        assertEquals("ep1314", windowed[index])
        assertEquals(items.last(), windowed.last())
    }

    @Test
    fun outOfRangeIndexIsClampedRatherThanThrowing() {
        val (windowed, index) = castQueueWindow(queue(1315), index = 99_999)
        assertTrue(index in windowed.indices)
        assertEquals("ep1314", windowed[index])
    }
}
