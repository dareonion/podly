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
    fun `a cooled-down episode stays out of the list`() = runBlocking {
        val first = repo.recommendations(profile, count = 10, nowMs = now, random = Random(1))
        val victim = first.first().episode.id
        repo.onSkipped(profile.id, victim, nowMs = now)
        val second = repo.recommendations(profile, count = 10, nowMs = now, random = Random(1))
        assertTrue(second.none { it.episode.id == victim })
        assertNotNull(db.radioDao().feedback(profile.id, victim))
    }
}
