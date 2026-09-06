package com.podly

import com.podly.ui.util.generatedText
import com.podly.ui.util.relativeAge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the bug where stale AI picks rendered as "generated Jul 26, 2026 (Jul
 * 26, 2026)": DateUtils.getRelativeTimeSpanString returns an absolute date once
 * the age passes its largest unit, so the parenthetical repeated the date.
 */
class RelativeAgeTest {

    private val now = 1_756_000_000_000L // fixed "now" so the test can't drift
    private fun ago(ms: Long) = relativeAge(now - ms, now)

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `sub-minute ages read as just now`() {
        assertEquals("just now", ago(0))
        assertEquals("just now", ago(59_000))
    }

    @Test
    fun `minutes hours and days are singular at one`() {
        assertEquals("1 minute ago", ago(minute))
        assertEquals("59 minutes ago", ago(59 * minute))
        assertEquals("1 hour ago", ago(hour))
        assertEquals("23 hours ago", ago(23 * hour))
        assertEquals("1 day ago", ago(day))
        assertEquals("6 days ago", ago(6 * day))
    }

    @Test
    fun `weeks months and years never fall back to a date`() {
        assertEquals("1 week ago", ago(7 * day))
        assertEquals("4 weeks ago", ago(29 * day))
        assertEquals("1 month ago", ago(30 * day))
        // The case from the screenshot: six-week-old picks.
        assertEquals("1 month ago", ago(42 * day))
        assertEquals("11 months ago", ago(340 * day))
        // Never "12 months ago": 30-day months overrun the year boundary.
        assertEquals("1 year ago", ago(364 * day))
        assertEquals("1 year ago", ago(365 * day))
        assertEquals("2 years ago", ago(800 * day))
    }

    @Test
    fun `a future timestamp does not produce a negative age`() {
        assertEquals("just now", relativeAge(now + 5 * minute, now))
    }

    @Test
    fun `generatedText appends a relative age and skips unknown times`() {
        assertNull(generatedText(0L, now))
        val text = generatedText(now - 42 * day, now)!!
        assertTrue(text, text.startsWith("generated "))
        assertTrue(text, text.endsWith("(1 month ago)"))
        // The date must not simply be repeated inside the parentheses.
        val date = text.removePrefix("generated ").substringBefore(" (")
        assertTrue(text, !text.endsWith("($date)"))
    }
}
