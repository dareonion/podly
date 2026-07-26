package com.podly

import com.podly.data.ArchiveEpisode
import com.podly.data.CachedArchive
import com.podly.data.EpisodeArchive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedArchiveTest {

    private class FakeArchive : EpisodeArchive {
        override val name = "Fake"
        var calls = 0
        var answer: List<ArchiveEpisode>? = null

        override suspend fun episodesFor(feedUrl: String, title: String): List<ArchiveEpisode>? {
            calls++
            return answer
        }
    }

    private fun episode(title: String) =
        ArchiveEpisode(title, null, "https://a.example/$title.mp3", null, 0L, null, null)

    @Test
    fun `serves a found answer from cache within the ttl`() = runBlocking {
        val fake = FakeArchive().apply { answer = listOf(episode("e1")) }
        var now = 0L
        val cached = CachedArchive(fake, ttlMs = 1000, clock = { now })

        assertEquals(1, cached.episodesFor("feed", "Show")!!.size)
        now = 999
        assertEquals(1, cached.episodesFor("feed", "Show")!!.size)
        assertEquals(1, fake.calls)
    }

    @Test
    fun `caches a definitive not-found (empty) answer`() = runBlocking {
        val fake = FakeArchive().apply { answer = emptyList() }
        val cached = CachedArchive(fake, ttlMs = 1000, clock = { 0L })

        assertEquals(0, cached.episodesFor("feed", "Show")!!.size)
        assertEquals(0, cached.episodesFor("feed", "Show")!!.size)
        assertEquals(1, fake.calls)
    }

    @Test
    fun `never caches a can't-answer null, so fixed creds apply immediately`() = runBlocking {
        val fake = FakeArchive().apply { answer = null }
        val cached = CachedArchive(fake, ttlMs = 1000, clock = { 0L })

        assertNull(cached.episodesFor("feed", "Show"))
        fake.answer = listOf(episode("e1"))
        assertEquals(1, cached.episodesFor("feed", "Show")!!.size)
        assertEquals(2, fake.calls)
    }

    @Test
    fun `expired entries are re-queried`() = runBlocking {
        val fake = FakeArchive().apply { answer = listOf(episode("e1")) }
        var now = 0L
        val cached = CachedArchive(fake, ttlMs = 1000, clock = { now })

        cached.episodesFor("feed", "Show")
        now = 1000
        cached.episodesFor("feed", "Show")
        assertEquals(2, fake.calls)
    }

    @Test
    fun `evicts the least recently used feed beyond maxEntries`() = runBlocking {
        val fake = FakeArchive().apply { answer = listOf(episode("e1")) }
        val cached = CachedArchive(fake, ttlMs = 1000, maxEntries = 2, clock = { 0L })

        cached.episodesFor("a", "A")
        cached.episodesFor("b", "B")
        cached.episodesFor("a", "A") // refresh a's recency
        cached.episodesFor("c", "C") // evicts b
        assertEquals(3, fake.calls)

        cached.episodesFor("a", "A") // still cached
        assertEquals(3, fake.calls)
        cached.episodesFor("b", "B") // was evicted -> re-queried
        assertEquals(4, fake.calls)
    }
}
