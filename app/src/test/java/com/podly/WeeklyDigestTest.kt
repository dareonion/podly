package com.podly

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podly.data.db.DigestRow
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PodcastEntity
import com.podly.data.db.PodlyDatabase
import com.podly.data.db.RadioPoolEntity
import com.podly.data.radio.RadioProfileStore
import com.podly.data.radio.RadioRepository
import com.podly.data.weekly.WeeklyIssue
import com.podly.data.weekly.WeeklyRepository
import com.podly.network.RemoteRecsApi
import com.podly.radio.RadioProfiles
import com.podly.ui.weekly.WeeklyUiState
import com.podly.ui.weekly.pickedByText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * The weekly digest rides the radio-pool machinery, so what matters is where its
 * rows may and may not surface: the newest week in grown-up radio with its blurb,
 * never in the toddler's, and a browsed back-issue nowhere but its own screen.
 */
@Config(application = Application::class)
@RunWith(AndroidJUnit4::class)
class WeeklyDigestTest {

    private lateinit var db: PodlyDatabase
    private lateinit var radio: RadioRepository
    private val now = 1_760_000_000_000L
    private val day = 86_400_000L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, PodlyDatabase::class.java)
            .allowMainThreadQueries().build()
        radio = RadioRepository(
            db.radioDao(), db.episodeDao(), db.podcastDao(),
            RadioProfileStore(context), RemoteRecsApi(),
        )
        db.podcastDao().insertIgnore(
            PodcastEntity(
                id = "show", title = "Chart show", author = "a", feedUrl = "https://f/show",
                artworkUrl = "https://art/show.jpg", description = null, subscribed = false,
            ),
        )
        db.episodeDao().insertIgnore(
            listOf(
                episode("both", now - 2 * day),
                episode("weekly-only", now - 3 * day, completed = true),
                episode("old-issue", now - 20 * day),
                episode("zh1", now - 1 * day),
            ),
        )
        db.radioDao().upsertPool(
            listOf(
                pool(RadioProfiles.YOU.id, "both", "Apple TW #3", 0.9f),
                pool(RadioProfiles.WEEKLY_ID, "both", "A reporter retraces the flood.", 0.8f),
                pool(RadioProfiles.WEEKLY_ID, "weekly-only", "An interview.", 0.95f, "en"),
                pool(RadioProfiles.WEEKLY_ID, "zh1", "主持人談城市。", 0.7f, "zh-Hant"),
                pool(WeeklyRepository.poolIdFor("2026-W36"), "old-issue", "Last month.", 1.0f),
                pool(RadioProfiles.TODDLER_ZH.id, "zh1", "Toddler pick", 0.5f),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun episode(id: String, pubDateMs: Long, completed: Boolean = false) = EpisodeEntity(
        id = id, podcastId = "show", podcastTitle = "Chart show", guid = id,
        title = "ep $id", description = null, audioUrl = "https://a/$id",
        pubDateMs = pubDateMs, durationMs = 30 * 60_000L, artworkUrl = null,
        completed = completed,
    )

    private fun pool(
        profileId: String,
        episodeId: String,
        reason: String,
        priority: Float,
        language: String = "en",
    ) = RadioPoolEntity(
        profileId = profileId, episodeId = episodeId, reason = reason, language = language,
        priority = priority, catalogVersion = 1L, addedAt = now,
    )

    @Test
    fun `a digest pick also in the chart pool reaches radio with its blurb`() = runBlocking {
        val picks = radio.recommendations(RadioProfiles.YOU, count = 20, nowMs = now, random = Random(1))
        assertEquals("A reporter retraces the flood.", picks.single { it.episode.id == "both" }.reason)
    }

    @Test
    fun `only the newest week reaches radio, and never the toddler's`() = runBlocking {
        val you = radio.recommendations(RadioProfiles.YOU, count = 20, nowMs = now, random = Random(1))
        assertTrue(you.none { it.reason == "Last month." })

        val toddlerPool = db.radioDao().discovery(
            profileId = RadioProfiles.TODDLER_ZH.id, nowMs = now, maxDurationMs = 0, limit = 50,
            excludedCategories = listOf(""), ambiguousCategories = listOf(""),
            exemptCategories = listOf(""),
        )
        // The toddler's own pool row for zh1 is untouched by the digest's copy.
        assertEquals(listOf("Toddler pick"), toddlerPool.map { it.reason })
        val toddler = radio.recommendations(
            RadioProfiles.TODDLER_ZH, count = 20, nowMs = now, random = Random(1),
        )
        assertTrue(toddler.none { it.reason in setOf("An interview.", "主持人談城市。") })
    }

    @Test
    fun `the screen lists a week best first with played rows and show artwork`() = runBlocking {
        val repo = WeeklyRepository(RemoteRecsApi(), radio, db.radioDao(), java.io.File("unused"))
        val rows = repo.entries(null).first()
        assertEquals(listOf("weekly-only", "both", "zh1"), rows.map { it.episodeId })
        // Completed episodes stay listed: a digest you are reading must not shrink.
        assertTrue(rows.first().completed)
        assertEquals("https://art/show.jpg", rows.first().artworkUrl)

        val back = repo.entries("2026-W36").first()
        assertEquals(listOf("old-issue"), back.map { it.episodeId })
    }

    @Test
    fun `the teaser alternates languages so both show`() {
        fun row(id: String, language: String) = DigestRow(
            episodeId = id, podcastId = "p", podcastTitle = "s", title = id, durationMs = null,
            pubDateMs = 0, artworkUrl = null, completed = false, playbackPositionMs = 0,
            language = language, priority = 0f, reason = null,
        )
        val rows = listOf(
            row("en1", "en"), row("en2", "en"), row("en3", "en"),
            row("zh1", "zh-Hans"), row("en4", "en"), row("zh2", "zh-Hant"),
        )
        assertEquals(
            listOf("en1", "zh1", "en2", "zh2", "en3"),
            WeeklyRepository.interleaveLanguages(rows, 5).map { it.episodeId },
        )
        assertEquals(listOf("en1", "en2"), WeeklyRepository.interleaveLanguages(rows.take(2), 5).map { it.episodeId })
    }

    @Test
    fun `issue ids are validated and namespaced away from radio profiles`() {
        assertTrue(WeeklyRepository.isValidIssueId("2026-W37"))
        assertFalse(WeeklyRepository.isValidIssueId("you"))
        assertFalse(WeeklyRepository.isValidIssueId("2026-W37/../radio"))
        assertEquals("weekly-2026-w37", WeeklyRepository.poolIdFor("2026-W37"))
        assertFalse(WeeklyRepository.poolIdFor("2026-W37") in RadioProfiles.POOL_IDS)
    }

    @Test
    fun `week navigation runs newest to oldest`() {
        val issues = listOf("2026-W37", "2026-W36", "2026-W35").map { WeeklyIssue(id = it) }
        val middle = WeeklyUiState(issues = issues, selectedId = "2026-W36")
        assertEquals("2026-W35", middle.older?.id)
        assertEquals("2026-W37", middle.newer?.id)
        val newest = middle.copy(selectedId = "2026-W37")
        assertNull(newest.newer)
        assertNull(WeeklyUiState(issues = issues, selectedId = null).older)
    }

    @Test
    fun `the byline names only the models that judged`() {
        assertEquals("picked by Claude and Codex", pickedByText(listOf("claude", "codex")))
        assertEquals("picked by Claude", pickedByText(listOf("claude")))
        assertNull(pickedByText(emptyList()))
    }
}
