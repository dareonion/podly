package com.podly

import com.podly.ui.util.formatDate
import com.podly.ui.util.publishedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line under every recommendation. Darren asked to see when an episode was
 * published; the relative age is there because a column of absolute dates does
 * not answer "is this list all ancient" at a glance.
 */
class PublishedTextTest {

    private val now = 1_756_000_000_000L
    private val day = 86_400_000L

    @Test
    fun `carries the date, the age and the duration`() {
        val text = publishedText(now - 14 * day, 47 * 60_000L, now)!!
        val parts = text.split(" · ")
        assertEquals(3, parts.size)
        assertEquals("2 weeks ago", parts[1])
        assertEquals("47m", parts[2])
        // The absolute date is locale-formatted; assert it is the same string the
        // rest of the app prints, not a spelling of it.
        assertEquals(formatDate(now - 14 * day), parts[0])
    }

    @Test
    fun `duration is optional`() {
        assertEquals(2, publishedText(now - day, null, now)!!.split(" · ").size)
        assertEquals(2, publishedText(now - day, 0L, now)!!.split(" · ").size)
    }

    @Test
    fun `an unknown publication date is omitted, never rendered as 1970`() {
        assertEquals("47m", publishedText(0L, 47 * 60_000L, now))
        assertNull(publishedText(-1L, null, now))
    }
}
