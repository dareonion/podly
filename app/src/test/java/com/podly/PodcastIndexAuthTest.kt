package com.podly

import com.podly.network.PodcastIndexApi
import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastIndexAuthTest {

    @Test
    fun `auth hash matches sha1 of key + secret + time`() {
        // Precomputed: sha1("testkeytestsecret1700000000")
        assertEquals(
            "90b6255b079ed6e48084f571b76958438661e91b",
            PodcastIndexApi.authHash("testkey", "testsecret", 1_700_000_000L),
        )
    }
}
