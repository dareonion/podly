package com.podly

import com.podly.network.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpsUpgradeTest {

    @Test
    fun `upgrades cleartext urls only`() {
        assertEquals(
            "https://feeds.feedburner.com/themothpodcast",
            Http.httpsUpgradeOrNull("http://feeds.feedburner.com/themothpodcast"),
        )
        assertEquals("https://x.example/feed", Http.httpsUpgradeOrNull("HTTP://x.example/feed"))
        assertNull(Http.httpsUpgradeOrNull("https://feeds.simplecast.com/HpGMoS4g"))
    }
}
