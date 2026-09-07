package com.podly

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PodcastEntity
import com.podly.data.db.PodlyDatabase
import com.podly.data.db.RadioFeedbackEntity
import com.podly.data.db.RadioPoolEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The SQL half of radio: what is eligible to be played. */
@Config(application = Application::class)
@RunWith(AndroidJUnit4::class)
class RadioDaoTest {

    private lateinit var db: PodlyDatabase
    private val now = 1_760_000_000_000L
    private val day = 86_400_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), PodlyDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun podcast(id: String, subscribed: Boolean) = PodcastEntity(
        id = id, title = "show $id", author = "a", feedUrl = "https://f/$id",
        artworkUrl = null, description = null, subscribed = subscribed,
    )

    private fun episode(
        id: String,
        podcastId: String,
        completed: Boolean = false,
        durationMs: Long? = 30 * 60_000L,
        inLibrary: Boolean = false,
    ) = EpisodeEntity(
        id = id, podcastId = podcastId, podcastTitle = "show $podcastId", guid = id,
        title = "ep $id", description = null, audioUrl = "https://a/$id",
        pubDateMs = now - day, durationMs = durationMs, artworkUrl = null,
        inLibrary = inLibrary, completed = completed,
    )

    private suspend fun seed() {
        db.podcastDao().insertIgnore(podcast("subbed", subscribed = true))
        db.podcastDao().insertIgnore(podcast("unsubbed", subscribed = false))
        db.episodeDao().insertIgnore(
            listOf(
                episode("a", "subbed"),
                episode("done", "subbed", completed = true),
                episode("long", "subbed", durationMs = 120 * 60_000L),
                episode("outside", "unsubbed"),
            ),
        )
    }

    private suspend fun backlog(
        includeUnsubscribed: Int = 1,
        maxDurationMs: Long = 0,
        restrictShows: Int = 0,
        allowed: List<String> = listOf(""),
    ) = db.radioDao().backlogRecent(
        profileId = "you", nowMs = now, includeUnsubscribed = includeUnsubscribed,
        restrictShows = restrictShows, allowedPodcastIds = allowed,
        maxDurationMs = maxDurationMs, minDurationMs = 0, limit = 50,
    ).map { it.episodeId }

    @Test
    fun `unsubscribed shows are included only when the profile allows it`() = runBlocking {
        seed()
        assertTrue("outside" in backlog(includeUnsubscribed = 1))
        assertTrue("outside" !in backlog(includeUnsubscribed = 0))
        // Subscribed episodes are there either way.
        assertTrue("a" in backlog(includeUnsubscribed = 0))
    }

    @Test
    fun `finished episodes and over-long ones are excluded`() = runBlocking {
        seed()
        assertTrue("done" !in backlog())
        assertTrue("long" !in backlog(maxDurationMs = 60 * 60_000L))
        assertTrue("a" in backlog(maxDurationMs = 60 * 60_000L))
    }

    @Test
    fun `an allowlist limits the backlog to the chosen shows`() = runBlocking {
        seed()
        val only = backlog(restrictShows = 1, allowed = listOf("unsubbed"))
        assertEquals(listOf("outside"), only)
    }

    @Test
    fun `a live cooldown hides an episode until it expires`() = runBlocking {
        seed()
        db.radioDao().putFeedback(
            RadioFeedbackEntity(
                profileId = "you", episodeId = "a", skipCount = 1,
                lastSkippedAt = now, blockedUntil = now + day,
            ),
        )
        assertTrue("a" !in backlog())
        db.radioDao().putFeedback(
            RadioFeedbackEntity(
                profileId = "you", episodeId = "a", skipCount = 1,
                lastSkippedAt = now - 2 * day, blockedUntil = now - day,
            ),
        )
        assertTrue("a" in backlog())
    }

    @Test
    fun `the notable pool is queried separately from a profile's own`() = runBlocking {
        seed()
        db.radioDao().upsertPool(
            listOf(
                RadioPoolEntity(profileId = "you", episodeId = "a", priority = 0.4f),
                RadioPoolEntity(
                    profileId = "notable", episodeId = "outside", priority = 0.99f,
                    reason = "Won a Peabody",
                ),
            ),
        )
        val own = db.radioDao()
            .discovery(profileId = "you", nowMs = now, maxDurationMs = 0, limit = 50)
            .map { it.episodeId }
        val notable = db.radioDao()
            .discovery(profileId = "notable", nowMs = now, maxDurationMs = 0, limit = 50)
        assertEquals(listOf("a"), own)
        assertEquals(listOf("outside"), notable.map { it.episodeId })
        // The citation rides along, so radio can say why it picked this.
        assertEquals("Won a Peabody", notable.single().reason)
    }

    @Test
    fun `a pool row whose episode is gone stops being a candidate`() = runBlocking {
        seed()
        db.radioDao().upsertPool(
            listOf(
                RadioPoolEntity(profileId = "you", episodeId = "outside", priority = 0.9f),
                RadioPoolEntity(profileId = "you", episodeId = "ghost", priority = 1.0f),
            ),
        )
        val ids = db.radioDao()
            .discovery(profileId = "you", nowMs = now, maxDurationMs = 0, limit = 50)
            .map { it.episodeId }
        // The JOIN against episodes is what guarantees every candidate is playable.
        assertEquals(listOf("outside"), ids)
    }
}
