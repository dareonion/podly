package com.podly

import com.podly.data.TranscriptIndexFile
import com.podly.data.canApplyTranscript
import com.podly.data.isOnTranscriptServer
import com.podly.data.resolveTranscriptUrl
import com.podly.data.transcriptCacheKey
import com.podly.data.transcriptServerUrl
import com.podly.data.transcriptTimingDiffers
import com.podly.network.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptIndexTest {
    @Test
    fun `reads the server catalogue and tolerates future and missing optional fields`() {
        val file = Http.json.decodeFromString<TranscriptIndexFile>(
            """{"generatedAt":"2026-11-04T23:00:00-08:00","future":true,"entries":[
              {"id":"episode","guid":"guid","audioUrl":"https://cdn/audio.mp3",
               "feedUrl":"https://show/feed","title":"第269期","durationMs":9168096,
               "url":"episode/transcript.1a2b3c4d.vtt","type":"text/vtt","language":"zh-CN","future":[]},
              {"id":"minimal","url":"minimal/t.vtt"}]}""",
        )
        assertEquals("2026-11-04T23:00:00-08:00", file.generatedAt)
        assertEquals(9_168_096L, file.entries[0].durationMs)
        assertEquals("zh-CN", file.entries[0].language)
        assertEquals("guid", file.entries[0].guid)
        assertEquals("text/vtt", file.entries[1].type)
        assertNull(file.entries[1].durationMs)
        assertNull(file.entries[1].language)
        assertNull(file.entries[1].guid)
        assertNull(Http.json.decodeFromString<TranscriptIndexFile>("""{"entries":[]}""").generatedAt)
    }

    @Test
    fun `resolves relative URLs against index in server directory`() {
        val server = "https://h:8015/transcripts"
        assertEquals("https://h:8015/transcripts/", transcriptServerUrl(server).toString())
        assertEquals("https://h:8015/transcripts/ep/t.vtt", resolveTranscriptUrl(server, "ep/t.vtt"))
        assertEquals("https://h:8015/transcripts/ep/t.vtt", resolveTranscriptUrl(server, "./ep/t.vtt"))
        assertEquals("https://h:8015/transcripts/t.vtt", resolveTranscriptUrl(server, "ep/../t.vtt"))
        assertEquals("https://h:8015/transcripts/t.vtt", resolveTranscriptUrl(server, "/transcripts/t.vtt"))
        assertEquals("https://h:8015/t.vtt", resolveTranscriptUrl("https://h:8015/", "t.vtt"))
    }

    @Test
    fun `origin and path boundaries are parsed rather than string prefixes`() {
        val server = "https://h:8015/a"
        listOf("https://h:8015/a", "https://h:8015/a/t.vtt", "https://H:8015/a/sub/t.vtt").forEach {
            assertTrue(it, isOnTranscriptServer(it, server))
        }
        listOf(
            "https://h:8015x/a/t.vtt", "https://h:80150/a/t.vtt", "http://h:8015/a/t.vtt",
            "https://h:8015/ab/t.vtt", "https://h.evil:8015/a/t.vtt", "https://h:8015/a/../ab/t.vtt",
            "https://h:8015/a%2Fb/t.vtt", "https://h:8015/A/t.vtt", "not a URL",
        ).forEach { assertFalse(it, isOnTranscriptServer(it, server)) }
        assertFalse(isOnTranscriptServer("https://h:8015x/t.vtt", "https://h:8015/"))
        assertFalse(isOnTranscriptServer(null, server))
        assertTrue(isOnTranscriptServer("https://h:443/t.vtt", "https://h/"))
    }

    @Test
    fun `rejects references outside server directory`() {
        listOf("../outside.vtt", "https://other/t.vtt", "//other/t.vtt", "file:///t.vtt").forEach {
            assertThrows(IllegalArgumentException::class.java) { resolveTranscriptUrl("https://h/a/", it) }
        }
    }

    @Test
    fun `only absent or same server transcripts may be replaced`() {
        val server = "https://h:8015/a/"
        assertTrue(canApplyTranscript(null, server))
        assertTrue(canApplyTranscript("https://h:8015/a/old.vtt", server))
        assertFalse(canApplyTranscript("https://publisher/transcript.vtt", server))
        assertFalse(canApplyTranscript("https://other-server:8015/transcript.vtt", server))
        assertFalse(canApplyTranscript("https://h:8015/ab/transcript.vtt", server))
    }

    @Test
    fun `cache key varies by URL and episode and is stable`() {
        assertEquals(transcriptCacheKey("ep", "https://h/1.vtt"), transcriptCacheKey("ep", "https://h/1.vtt"))
        assertNotEquals(transcriptCacheKey("ep", "https://h/1.vtt"), transcriptCacheKey("ep", "https://h/2.vtt"))
        assertNotEquals(transcriptCacheKey("ep", "https://h/1.vtt"), transcriptCacheKey("other", "https://h/1.vtt"))
        assertFalse(transcriptCacheKey("ep", "https://h/1.vtt").contains('/'))
    }

    @Test
    fun `timing note requires known durations differing by more than ten seconds`() {
        assertFalse(transcriptTimingDiffers(null, 90_000))
        assertFalse(transcriptTimingDiffers(90_000, 0))
        assertFalse(transcriptTimingDiffers(90_000, null))
        assertFalse(transcriptTimingDiffers(90_000, 100_000))
        assertFalse(transcriptTimingDiffers(90_000, 80_000))
        assertTrue(transcriptTimingDiffers(90_000, 100_001))
        assertTrue(transcriptTimingDiffers(90_000, 79_999))
    }
}
