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

    /** Room expands an empty list to `IN ()`, which SQLite rejects. */
    private val NO_CATEGORY = "\u0000"

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
        excluded: List<String> = listOf(NO_CATEGORY),
        exempt: List<String> = listOf(NO_CATEGORY),
        ambiguous: List<String> = listOf(NO_CATEGORY),
    ) = db.radioDao().backlogRecent(
        profileId = "you", nowMs = now, includeUnsubscribed = includeUnsubscribed,
        restrictShows = restrictShows, allowedPodcastIds = allowed,
        maxDurationMs = maxDurationMs, minDurationMs = 0, limit = 50,
        excludedCategories = excluded, exemptCategories = exempt,
        ambiguousCategories = ambiguous,
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
            .discovery(
                profileId = "you", nowMs = now, maxDurationMs = 0, limit = 50,
                excludedCategories = listOf(NO_CATEGORY),
                exemptCategories = listOf(NO_CATEGORY),
                ambiguousCategories = listOf(NO_CATEGORY),
            )
            .map { it.episodeId }
        val notable = db.radioDao()
            .discovery(
                profileId = "notable", nowMs = now, maxDurationMs = 0, limit = 50,
                excludedCategories = listOf(NO_CATEGORY),
                exemptCategories = listOf(NO_CATEGORY),
                ambiguousCategories = listOf(NO_CATEGORY),
            )
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
            .discovery(
                profileId = "you", nowMs = now, maxDurationMs = 0, limit = 50,
                excludedCategories = listOf(NO_CATEGORY),
                exemptCategories = listOf(NO_CATEGORY),
                ambiguousCategories = listOf(NO_CATEGORY),
            )
            .map { it.episodeId }
        // The JOIN against episodes is what guarantees every candidate is playable.
        assertEquals(listOf("outside"), ids)
    }

    @Test
    fun `a profile's excluded categories drop the show from both buckets`() = runBlocking {
        seed()
        db.radioDao().upsertPool(
            listOf(RadioPoolEntity(profileId = "you", episodeId = "outside", priority = 0.9f)),
        )
        db.podcastDao().replaceCategories("unsubbed", listOf("Kids & Family", "Stories for Kids"))
        db.podcastDao().replaceCategories("subbed", listOf("News"))

        val excluded = listOf("kids & family")
        assertTrue("outside" !in backlog(excluded = excluded))
        assertTrue("a" in backlog(excluded = excluded))
        val picks = db.radioDao().discovery(
            profileId = "you", nowMs = now, maxDurationMs = 0, limit = 50,
            excludedCategories = excluded, exemptCategories = listOf(NO_CATEGORY),
            ambiguousCategories = listOf(NO_CATEGORY),
        )
        assertTrue(picks.isEmpty())
        // Without the exclusion the same show is a candidate, so the filter is
        // what removed it and not some other predicate.
        assertTrue("outside" in backlog())
    }

    @Test
    fun `an exempt category rescues a show from an exclusion`() = runBlocking {
        seed()
        // Apple files parenting shows for adults under Kids & Family; excluding
        // that genre alone would take them along with the children's programming.
        db.podcastDao().replaceCategories("unsubbed", listOf("Kids & Family", "Parenting"))
        db.podcastDao().replaceCategories("subbed", listOf("Kids & Family", "Stories for Kids"))
        val ids = backlog(
            excluded = listOf("stories for kids"),
            ambiguous = listOf("kids & family"),
            exempt = listOf("parenting"),
        )
        assertTrue("outside" in ids)
        assertTrue("a" !in ids)
    }

    @Test
    fun `an unambiguous genre outranks the exemption`() = runBlocking {
        seed()
        // 交通工具故事大集合 declares Parenting *and* Stories for Kids, and is a
        // children's show: publishers tag liberally, so the specific genre wins.
        db.podcastDao().replaceCategories(
            "unsubbed", listOf("Kids & Family", "Parenting", "Stories for Kids"),
        )
        val ids = backlog(
            excluded = listOf("stories for kids"),
            ambiguous = listOf("kids & family"),
            exempt = listOf("parenting"),
        )
        assertTrue("outside" !in ids)
    }

    @Test
    fun `categories are stored lowercased and an empty list never clears them`() = runBlocking {
        seed()
        db.podcastDao().replaceCategories("subbed", listOf(" True Crime ", "true crime", "News"))
        assertEquals(setOf("true crime", "news"), db.podcastDao().categoriesFor("subbed").toSet())
        // A feed that stops declaring categories, or a failed lookup, must not
        // silently un-filter a show that was correctly excluded yesterday.
        db.podcastDao().replaceCategories("subbed", emptyList())
        assertEquals(setOf("true crime", "news"), db.podcastDao().categoriesFor("subbed").toSet())
    }
}
