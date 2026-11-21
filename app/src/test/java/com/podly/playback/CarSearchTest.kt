package com.podly.playback

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.EpisodeSearchRow
import com.podly.data.db.PodcastEntity
import com.podly.data.db.PodcastEpisodeSortOrder
import com.podly.data.db.PodlyDatabase
import com.podly.data.db.SubscribedPodcast
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Plain Application: PodlyApp's onCreate spins up WorkManager, which these tests don't need.
@Config(application = Application::class)
@RunWith(AndroidJUnit4::class)
class CarSearchTest {

    private lateinit var db: PodlyDatabase
    private lateinit var search: CarSearch
    private val showTitles = mapOf(
        "daily" to "The Daily",
        "story" to "Sold a Story",
        "zh" to "☀️ 全球串連早安新聞｜Morning Taiwan Glocal News",
        "other" to "Unfollowed Show",
        "daily2" to "The Daily Extra",
    )
    private val collator = Collator.getInstance(Locale.US)

    @Before
    fun createDb() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            PodlyDatabase::class.java,
        ).allowMainThreadQueries().build()
        search = CarSearch(db) { collator }
        val podcasts = db.podcastDao()
        podcasts.insertIgnore(podcast("daily", showTitles.getValue("daily"), author = "The New York Times"))
        podcasts.insertIgnore(
            podcast("story", showTitles.getValue("story"), sortOrder = PodcastEpisodeSortOrder.OLDEST_FIRST)
        )
        podcasts.insertIgnore(podcast("zh", showTitles.getValue("zh"), author = "浩爾&小路"))
        podcasts.insertIgnore(podcast("other", showTitles.getValue("other"), subscribed = false))
        db.episodeDao().insertIgnore(
            listOf(
                episode("d1", "daily", "Monday news", pubDateMs = 1, completed = true),
                episode("d2", "daily", "Tuesday news", pubDateMs = 2),
                episode("d3", "daily", "Wednesday news", pubDateMs = 3),
                episode("s1", "story", "Chapter One", pubDateMs = 1, completed = true),
                episode("s2", "story", "Chapter Two", pubDateMs = 2),
                episode("s3", "story", "Chapter Three", pubDateMs = 3),
                episode("z1", "zh", "1113 全球讀報", pubDateMs = 5),
                episode("o1", "other", "A Story Worth Saving", pubDateMs = 9).copy(inLibrary = true),
                episode("o2", "other", "Not saved story", pubDateMs = 10),
            )
        )
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun resultsListMatchingShowsThenEpisodesNewestFirst() = runBlocking {
        val ids = search.results("story").map { it.mediaId }
        assertEquals(
            listOf(
                MediaIds.podcast("story"),
                // Saved from a show you don't follow: plays alone. The unsaved one isn't in the library.
                MediaIds.episode("o1"),
                MediaIds.showEpisode("s3"),
                MediaIds.showEpisode("s2"),
                MediaIds.showEpisode("s1"),
            ),
            ids,
        )
    }

    @Test
    fun everyWordMustMatchAndCaseDoesNotMatter() = runBlocking {
        assertEquals(listOf(MediaIds.showEpisode("d2")), search.results("TUESDAY   news").map { it.mediaId })
        assertEquals(emptyList<String>(), search.results("tuesday chapter").map { it.mediaId })
        assertEquals(emptyList<String>(), search.results("   ").map { it.mediaId })
    }

    @Test
    fun aChinesePhraseMatchesAsWritten() = runBlocking {
        assertEquals(
            listOf(MediaIds.podcast("zh"), MediaIds.showEpisode("z1")),
            search.results("早安新聞").map { it.mediaId },
        )
    }

    @Test
    fun playingAShowStartsAtItsFirstUnfinishedEpisodeAndPlaysOn() = runBlocking {
        // Oldest first: chapter one is finished, so two, then three.
        assertEquals(listOf("s2", "s3"), search.voiceQueue("sold a story"))
        // Newest first: Wednesday, then back to Tuesday; Monday is finished.
        assertEquals(listOf("d3", "d2"), search.voiceQueue("the daily"))
    }

    @Test
    fun playingAShowResumesTheEpisodeHeardMostRecently() = runBlocking {
        db.episodeDao().updateProgress("d2", positionMs = 60_000, completed = false, lastPlayedAt = 100)
        assertEquals(listOf("d2"), search.voiceQueue("the daily"))
    }

    @Test
    fun aShowWithNothingUnfinishedIsNoMatch() = runBlocking {
        listOf("d1", "d2", "d3").forEach { db.episodeDao().setPlayed(it, true) }
        assertNull(search.voiceQueue("the daily"))
    }

    @Test
    fun aFinishedShowIsPassedOverForTheNextMatch() = runBlocking {
        db.podcastDao().insertIgnore(podcast("daily2", "The Daily Extra"))
        db.episodeDao().insertIgnore(listOf(episode("x1", "daily2", "Extra one", pubDateMs = 1)))
        listOf("d1", "d2", "d3").forEach { db.episodeDao().setPlayed(it, true) }
        // "daily" matches both shows; The Daily has nothing unfinished.
        assertEquals(listOf("x1"), search.voiceQueue("daily extra"))
        assertEquals(listOf("x1"), search.voiceQueue("daily"))
    }

    @Test
    fun anEpisodeByTitlePlaysOnThroughItsShowUnlessItsShowIsNotFollowed() = runBlocking {
        assertEquals(listOf("s2", "s3"), search.voiceQueue("chapter two"))
        assertEquals(listOf("o1"), search.voiceQueue("a story worth saving"))
    }

    @Test
    fun nothingMatchingIsNull() = runBlocking {
        assertNull(search.voiceQueue("bluey"))
        assertNull(search.voiceQueue(""))
    }

    @Test
    fun anExactShowTitleBeatsAnExactEpisodeTitleWhichBeatsPartialMatches() {
        val shows = listOf(
            SubscribedPodcast(podcast("news", "News", author = "Someone"), lastPlayedAt = 0),
            SubscribedPodcast(podcast("recent", "Recent Talk", author = "News Corp"), lastPlayedAt = 999),
        )
        val rows = listOf(
            row("ep-news", "News", "Recent Talk", pubDateMs = 5),
            row("ep-latest", "More news today", "Recent Talk", pubDateMs = 9),
        )
        // Exact show title, though another show matches by author and was played later.
        assertEquals(VoicePick.Show("news"), voiceCandidates("news", shows, rows, collator).first())
        // Exact episode title beats a partial show match.
        val noExactShow = shows.drop(1)
        assertEquals(VoicePick.Episode(rows[0]), voiceCandidates("news", noExactShow, rows, collator).first())
        // An exact show title is the only candidate there is.
        assertEquals(listOf(VoicePick.Show("news")), voiceCandidates("news", shows, rows, collator).toList())
        // Then a partial show match, then the newest partial episode match.
        assertEquals(VoicePick.Show("recent"), voiceCandidates("corp", shows, rows, collator).first())
        assertEquals(VoicePick.Episode(rows[1]), voiceCandidates("today", shows, rows, collator).first())
    }

    @Test
    fun comparableTitlesIgnoreLeadingSymbolsSpacingAndCase() {
        assertEquals(comparableTitle("全球串連早安新聞"), comparableTitle("☀️ 全球串連早安新聞"))
        assertEquals("the daily", comparableTitle("  The　Daily "))
    }

    private fun podcast(
        id: String,
        title: String,
        author: String = "Author",
        subscribed: Boolean = true,
        sortOrder: PodcastEpisodeSortOrder = PodcastEpisodeSortOrder.NEWEST_FIRST,
    ) = PodcastEntity(
        id = id,
        title = title,
        author = author,
        feedUrl = "https://example.com/$id.xml",
        artworkUrl = null,
        description = null,
        subscribed = subscribed,
        episodeSortOrder = sortOrder,
    )

    private fun episode(id: String, podcastId: String, title: String, pubDateMs: Long, completed: Boolean = false) =
        EpisodeEntity(
            id = id,
            podcastId = podcastId,
            podcastTitle = showTitles.getValue(podcastId),
            guid = id,
            title = title,
            description = null,
            audioUrl = "https://example.com/$id.mp3",
            pubDateMs = pubDateMs,
            durationMs = null,
            artworkUrl = null,
            completed = completed,
        )

    private fun row(id: String, title: String, show: String, pubDateMs: Long) =
        EpisodeSearchRow(id, title, show, show, pubDateMs, subscribed = true)
}
