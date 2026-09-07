package com.podly

import com.podly.radio.RadioCategories
import com.podly.radio.RadioProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What "You" refuses to suggest, and how the screen says so. */
class RadioCategoriesTest {

    @Test
    fun `the You profile excludes kids, true crime and religion`() {
        val excluded = RadioProfiles.YOU.excludedCategories
        // "Kids & Family" is ambiguous, not excluded outright: parenting shows
        // live there too. The unambiguous subgenres are the hard exclusions.
        assertTrue("kids & family" !in excluded)
        assertTrue("kids & family" in RadioProfiles.YOU.ambiguousCategories)
        assertTrue("stories for kids" in excluded)
        assertTrue("true crime" in excluded)
        assertTrue("christianity" in excluded)
        // Apple files parenting shows under Kids & Family, so the exclusion has
        // to catch them and the exemption has to give them back.
        val exempt = RadioProfiles.YOU.exemptCategories
        assertTrue("parenting" in exempt)
        assertTrue("pets & animals" in exempt)
        assertEquals(exempt, exempt.map { it.lowercase() }.toSet())
        // Every entry is lowercase: podcast_categories stores lowercase, and a
        // capitalised entry here would silently match nothing.
        assertEquals(excluded, excluded.map { it.lowercase() }.toSet())
    }

    @Test
    fun `the toddler profile excludes nothing, kids shows being the point`() {
        assertTrue(RadioProfiles.TODDLER_ZH.excludedCategories.isEmpty())
        assertNull(RadioCategories.label(RadioProfiles.TODDLER_ZH))
    }

    @Test
    fun `the label names groups rather than reading out raw genres`() {
        assertEquals("kids, true crime, religion", RadioCategories.label(RadioProfiles.YOU))
        // A profile that excludes only one group says only that.
        val crimeOnly = RadioProfiles.YOU.copy(
            excludedCategories = RadioCategories.TRUE_CRIME,
            ambiguousCategories = emptySet(),
        )
        assertEquals("true crime", RadioCategories.label(crimeOnly))
    }
}
