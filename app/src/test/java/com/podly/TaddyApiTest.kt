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
    fun `parses a series and its episodes from a taddy response`() {
        val json = """
            {"data":{"getPodcastSeries":{"uuid":"s1","name":"The Interview",
              "rssUrl":"https://feeds.simplecast.com/HpGMoS4g","episodes":[
              {"name":"Robby Hoffman on The Interview","description":"A funny one.",
               "audioUrl":"https://cdn.example.com/hoffman.mp3","datePublished":1719000000,
               "duration":2400,"guid":"guid-1","imageUrl":"https://img.example.com/a.jpg"}
            ]}}}
        """.trimIndent()
        val series = TaddyApi.parse(json)!!
        assertEquals("The Interview", series.name)
        assertEquals("https://feeds.simplecast.com/HpGMoS4g", series.rssUrl)
        assertEquals(1, series.episodes.size)
        assertEquals("Robby Hoffman on The Interview", series.episodes[0].name)
        assertEquals("https://cdn.example.com/hoffman.mp3", series.episodes[0].audioUrl)
        assertEquals(1719000000L, series.episodes[0].datePublished)
        assertEquals(2400L, series.episodes[0].duration)
        assertEquals("guid-1", series.episodes[0].guid)
    }

    @Test
    fun `returns null when the series is not found`() {
        assertNull(TaddyApi.parse("""{"data":{"getPodcastSeries":null}}"""))
        assertNull(TaddyApi.parse("""{"data":null}"""))
    }

    @Test
    fun `throws on a graphql error response`() {
        // Taddy reports bad creds as HTTP 200 + errors; parse must not read that as
        // "not found" or the archive fallback chain hides the misconfiguration.
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
        val series = TaddyApi.parse(json)!!
        assertEquals(1, series.episodes.size)
        assertEquals("E", series.episodes[0].name)
        assertNull(series.episodes[0].guid)
        assertEquals(0L, series.episodes[0].datePublished)
    }
}
