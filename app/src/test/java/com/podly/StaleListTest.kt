package com.podly

import com.podly.ui.discover.STALE_LIST_MS
import com.podly.ui.discover.isStaleList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleListTest {
    private val now = 1_791_900_000_000L // 2026-10-13
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `a list generated in July is stale in October`() {
        val july26 = now - 79 * day
        assertTrue(isStaleList(july26, now))
    }

    @Test
    fun `a list from this week is shown`() {
        assertFalse(isStaleList(now - 3 * day, now))
        assertFalse(isStaleList(now - STALE_LIST_MS, now))
    }

    @Test
    fun `an unknown generation time is not treated as stale`() {
        // Zero means not loaded yet; hiding then would make the section flicker away.
        assertFalse(isStaleList(0, now))
    }
}
