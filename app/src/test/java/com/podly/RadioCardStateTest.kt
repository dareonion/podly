package com.podly

import com.podly.radio.RadioProfiles
import com.podly.ui.radio.RadioCardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioCardStateTest {

    @Test
    fun `selecting the other chip does not make its radio look like it is playing`() {
        // You radio is playing; the Toddler chip is selected.
        val state = RadioCardState(profile = RadioProfiles.TODDLER_ZH, playingProfileId = RadioProfiles.YOU.id)
        assertFalse(state.isActive)
        assertEquals(RadioProfiles.YOU, state.otherProfilePlaying)
    }

    @Test
    fun `the selected profile's own session is active`() {
        val state = RadioCardState(profile = RadioProfiles.YOU, playingProfileId = RadioProfiles.YOU.id)
        assertTrue(state.isActive)
        assertNull(state.otherProfilePlaying)
    }

    @Test
    fun `no session means neither active nor another profile playing`() {
        val state = RadioCardState(profile = RadioProfiles.YOU, playingProfileId = null)
        assertFalse(state.isActive)
        assertNull(state.otherProfilePlaying)
    }
}
