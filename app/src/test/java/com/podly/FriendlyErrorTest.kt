package com.podly

import com.podly.ui.util.friendlyError
import com.podly.ui.util.friendlyErrorText
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The UI used to render these strings verbatim; see ErrorText.kt. */
class FriendlyErrorTest {

    @Test
    fun `anthropic credit-balance body becomes an actionable sentence`() {
        val raw = """400: {"type":"error","error":{"type":"invalid_request_error","message":""" +
            """"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing """ +
            """to upgrade or purchase credits."},"request_id":"req_011CeXJZrxyDpaGZuMSGaxcn"}"""
        val text = friendlyErrorText(raw)
        assertEquals(
            "That account is out of API credits. Add credits for the key in Settings, or switch provider.",
            text,
        )
        assertTrue(text, !text.contains("request_id"))
        assertTrue(text, !text.contains("{"))
    }

    @Test
    fun `dns failure reads as a network problem`() {
        val expected = "No internet connection. Check your network and try again."
        assertEquals(
            expected,
            friendlyErrorText("""Unable to resolve host "api.anthropic.com": No address associated with hostname"""),
        )
        assertEquals(expected, friendlyError(UnknownHostException("api.anthropic.com")))
        // Wrapped by an SDK, which is how it actually arrives.
        assertEquals(expected, friendlyError(IOException("Request failed", UnknownHostException("api.anthropic.com"))))
    }

    @Test
    fun `timeouts rejected keys and rate limits are named`() {
        assertEquals("The request timed out. Try again.", friendlyError(SocketTimeoutException("timeout")))
        assertTrue(friendlyErrorText("""{"type":"authentication_error","message":"invalid x-api-key"}""")
            .startsWith("That API key was rejected"))
        assertTrue(friendlyErrorText("""{"error":{"type":"rate_limit_error","message":"slow down"}}""")
            .startsWith("Rate limited"))
    }

    @Test
    fun `an unmapped message survives, trimmed, and empty input still says something`() {
        assertEquals("Feed returned HTTP 503", friendlyErrorText("  Feed returned HTTP 503  "))
        assertEquals("Something went wrong. Try again.", friendlyErrorText(""))
        assertEquals("Something went wrong. Try again.", friendlyError(IOException()))
        assertEquals(200, friendlyErrorText("x".repeat(500)).length)
    }
}
