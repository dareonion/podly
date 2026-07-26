package com.podly

import com.podly.network.TaddyApi
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaddyApiTest {

    @Test
    fun `parses episodes from a taddy response`() {
        val json = """
            {"data":{"getPodcastSeries":{"uuid":"s1","episodes":[
              {"name":"Robby Hoffman on The Interview","description":"A funny one.",
               "audioUrl":"https://cdn.example.com/hoffman.mp3","datePublished":1719000000,
               "duration":2400,"guid":"guid-1","imageUrl":"https://img.example.com/a.jpg"}
            ]}}}
        """.trimIndent()
        val eps = TaddyApi.parse(json)
        assertEquals(1, eps.size)
        assertEquals("Robby Hoffman on The Interview", eps[0].name)
        assertEquals("https://cdn.example.com/hoffman.mp3", eps[0].audioUrl)
        assertEquals(1719000000L, eps[0].datePublished)
        assertEquals(2400L, eps[0].duration)
        assertEquals("guid-1", eps[0].guid)
    }

    @Test
    fun `returns empty when the series is not found`() {
        assertTrue(TaddyApi.parse("""{"data":{"getPodcastSeries":null}}""").isEmpty())
        assertTrue(TaddyApi.parse("""{"data":null}""").isEmpty())
    }

    @Test
    fun `throws on a graphql error response`() {
        // Taddy reports bad creds as HTTP 200 + errors; parse must not read that as
        // "no episodes" or the archive fallback chain hides the misconfiguration.
        val json = """
            {"errors":[{"message":"The X-API-KEY or X-USER-ID headers are missing or invalid.",
              "code":"API_KEY_INVALID"}]}
        """.trimIndent()
        val e = assertThrows(IOException::class.java) { TaddyApi.parse(json) }
        assertTrue(e.message!!.contains("API_KEY_INVALID"))
    }

    @Test
    fun `tolerates unknown fields and missing optionals`() {
        val json = """{"data":{"getPodcastSeries":{"episodes":[{"name":"E","audioUrl":"u","extra":123}]}}}"""
        val eps = TaddyApi.parse(json)
        assertEquals(1, eps.size)
        assertEquals("E", eps[0].name)
        assertNull(eps[0].guid)
        assertEquals(0L, eps[0].datePublished)
    }
}
