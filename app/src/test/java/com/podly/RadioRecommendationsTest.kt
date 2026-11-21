package com.podly

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PodcastEntity
import com.podly.data.db.PodlyDatabase
import com.podly.data.db.RadioPoolEntity
import com.podly.data.db.RadioSource
import com.podly.data.radio.RadioProfileStore
import com.podly.data.radio.RadioRepository
import com.podly.network.RemoteRecsApi
import com.podly.radio.RadioProfiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * The browsable picks list. Its one hard contract: reading it commits to
 * nothing. Marking sixty episodes served because the user scrolled past them
 * would cool down the whole pool and empty the radio they just opened.
 */
@Config(application = Application::class)
@RunWith(AndroidJUnit4::class)
class RadioRecommendationsTest {

    private lateinit var db: PodlyDatabase
    private lateinit var repo: RadioRepository
    private val now = 1_760_000_000_000L
    private val day = 86_400_000L
    private val profile = RadioProfiles.YOU

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, PodlyDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RadioRepository(
            db.radioDao(), db.episodeDao(), db.podcastDao(),
            RadioProfileStore(context), RemoteRecsApi(),
        )
        db.podcastDao().insertIgnore(
            PodcastEntity(
                id = "subbed", title = "Backlog show", author = "a",
                feedUrl = "https://f/subbed", artworkUrl = null, description = null,
                subscribed = true,
            ),
        )
        db.podcastDao().insertIgnore(
            PodcastEntity(
                id = "found", title = "Discovered show", author = "a",
                feedUrl = "https://f/found", artworkUrl = null, description = null,
                subscribed = false,
            ),
        )
        db.episodeDao().insertIgnore(
            (1..4).map { episode("back$it", "subbed", now - it * day) } +
                episode("pool1", "found", now - 2 * day),
        )
        db.radioDao().upsertPool(
            listOf(
                RadioPoolEntity(
                    profileId = profile.id, episodeId = "pool1", source = RadioSource.CATALOG,
                    reason = "Top 10 in Taiwan", language = "en", priority = 1.0f,
                    catalogVersion = 1L, addedAt = now, expiresAt = now + 30 * day,
                ),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun episode(id: String, podcastId: String, pubDateMs: Long) = EpisodeEntity(
        id = id, podcastId = podcastId, podcastTitle = "show $podcastId", guid = id,
        title = "ep $id", description = null, audioUrl = "https://a/$id",
        pubDateMs = pubDateMs, durationMs = 30 * 60_000L, artworkUrl = null,
    )

    @Test
    fun `returns playable episodes with their citation`() = runBlocking {
        val picks = repo.recommendations(profile, count = 10, nowMs = now, random = Random(1))
        assertTrue(picks.size >= 4)
        // pool1's show is unsubscribed, so the backlog query sees it too; it must
        // still arrive as a pool pick, with its citation.
        val pooled = picks.single { it.episode.id == "pool1" }
        assertEquals("Top 10 in Taiwan", pooled.reason)
        assertTrue(pooled.discovery)
        val backlog = picks.first { it.episode.id.startsWith("back") }
        assertNull(backlog.reason)
        // Every row is a real episode with a playable URL — the list can't offer
        // an id the playback service would silently drop.
        assertTrue(picks.all { it.episode.audioUrl.isNotBlank() })
        assertEquals(picks.size, picks.distinctBy { it.episode.id }.size)
    }

    @Test
    fun `browsing marks nothing served, playing does`() = runBlocking {
        val picks = repo.recommendations(profile, count = 10, nowMs = now, random = Random(1))
        for (pick in picks) {
            assertNull(
                "browsing must not record a serve for ${pick.episode.id}",
                db.radioDao().feedback(profile.id, pick.episode.id),
            )
        }
        // The same ranking through the play path does record one.
        repo.nextBatch(profile, count = 1, nowMs = now, random = Random(1))
        assertEquals(
            1,
            picks.count { db.radioDao().feedback(profile.id, it.episode.id) != null },
        )
    }

    @Test
    fun `a batch the caller may discard is marked served only when it says so`() = runBlocking {
        val batch = repo.nextBatch(profile, count = 2, nowMs = now, random = Random(1), recordServes = false)
        assertTrue(batch.isNotEmpty())
        assertTrue(batch.all { db.radioDao().feedback(profile.id, it.id) == null })
        repo.recordServed(profile.id, batch.map { it.id }, nowMs = now)
        assertTrue(batch.all { db.radioDao().feedback(profile.id, it.id)?.serveCount == 1 })
    }

    @Test
    fun `a cooled-down episode stays out of the list`() = runBlocking {
        val first = repo.recommendations(profile, count = 10, nowMs = now, random = Random(1))
        val victim = first.first().episode.id
        repo.onSkipped(profile.id, victim, nowMs = now)
        val second = repo.recommendations(profile, count = 10, nowMs = now, random = Random(1))
        assertTrue(second.none { it.episode.id == victim })
        assertNotNull(db.radioDao().feedback(profile.id, victim))
    }

    @Test
    fun `the toddler's pool plays only the ticked shows, and nothing when none are`() = runBlocking {
        val toddler = RadioProfiles.TODDLER_ZH
        val store = RadioProfileStore(ApplicationProvider.getApplicationContext<Application>())
        db.podcastDao().insertIgnore(
            PodcastEntity(
                id = "chosen", title = "Chosen show", author = "a",
                feedUrl = "https://f/chosen", artworkUrl = null, description = null,
                subscribed = true,
            ),
        )
        // Short enough for the toddler's 15-minute cap, so only the allowlist decides.
        db.episodeDao().insertIgnore(
            listOf("kid1" to "chosen", "kid2" to "found").map { (id, show) ->
                episode(id, show, now - day).copy(durationMs = 10 * 60_000L)
            },
        )
        db.radioDao().upsertPool(
            listOf("kid1", "kid2").map {
                RadioPoolEntity(
                    profileId = toddler.id, episodeId = it, source = RadioSource.CATALOG,
                    reason = "Chart pick", language = "zh", priority = 1.0f,
                    // No expiry: poolCount reads the real clock, not this test's `now`.
                    catalogVersion = 1L, addedAt = now, expiresAt = null,
                )
            },
        )
        try {
            store.setSelectedShows(toddler.id, emptySet())
            assertTrue(repo.recommendations(toddler, count = 10, nowMs = now, random = Random(1)).isEmpty())
            assertTrue(repo.nextBatch(toddler, count = 5, nowMs = now, random = Random(1)).isEmpty())
            assertEquals(0, repo.poolCount(toddler.id).first())

            store.setSelectedShows(toddler.id, setOf("chosen"))
            val picks = repo.recommendations(toddler, count = 10, nowMs = now, random = Random(1))
            assertEquals(listOf("kid1"), picks.map { it.episode.id })
            assertEquals(1, repo.poolCount(toddler.id).first())
        } finally {
            store.setSelectedShows(toddler.id, emptySet())
        }
    }
}
