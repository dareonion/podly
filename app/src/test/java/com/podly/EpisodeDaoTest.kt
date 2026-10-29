package com.podly

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podly.data.db.DownloadStatus
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PodcastEntity
import com.podly.data.db.PodlyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Plain Application: PodlyApp's onCreate spins up WorkManager, which these tests don't need.
@Config(application = Application::class)
@RunWith(AndroidJUnit4::class)
class EpisodeDaoTest {

    private lateinit var db: PodlyDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            PodlyDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun startedUndownloadedOnce_selectsOnlyResumableRecentEpisodes() = runBlocking {
        val now = 1_000_000_000_000L
        val dao = db.episodeDao()
        dao.insertIgnore(
            listOf(
                episode("started-recent", positionMs = 60_000, lastPlayedAt = now - 1),
                episode("started-earlier", positionMs = 30_000, lastPlayedAt = now - 2),
                episode("never-started", positionMs = 0, lastPlayedAt = 0),
                episode("completed", positionMs = 60_000, lastPlayedAt = now - 3, completed = true),
                episode(
                    "downloaded",
                    positionMs = 60_000,
                    lastPlayedAt = now - 4,
                    downloadStatus = DownloadStatus.DONE,
                ),
                episode(
                    "already-queued",
                    positionMs = 60_000,
                    lastPlayedAt = now - 5,
                    downloadStatus = DownloadStatus.QUEUED,
                ),
                episode("blocked", positionMs = 60_000, lastPlayedAt = now - 6, autoDownloadBlocked = true),
                episode("started-long-ago", positionMs = 60_000, lastPlayedAt = now - 100),
            )
        )

        val picked = dao.startedUndownloadedOnce(since = now - 50, limit = 20)
        assertEquals(listOf("started-recent", "started-earlier"), picked.map { it.id })

        val limited = dao.startedUndownloadedOnce(since = now - 50, limit = 1)
        assertEquals(listOf("started-recent"), limited.map { it.id })
    }

    @Test
    fun subscribedWithLastPlayed_takesEachShowsLatestPlayAndSkipsUnsubscribed() = runBlocking {
        val podcasts = db.podcastDao()
        podcasts.insertIgnore(podcast("heard", subscribed = true))
        podcasts.insertIgnore(podcast("never", subscribed = true))
        podcasts.insertIgnore(podcast("gone", subscribed = false))
        db.episodeDao().insertIgnore(
            listOf(
                episode("heard-old", positionMs = 1, lastPlayedAt = 100).copy(podcastId = "heard"),
                episode("heard-new", positionMs = 1, lastPlayedAt = 900).copy(podcastId = "heard"),
                episode("never-1", positionMs = 0, lastPlayedAt = 0).copy(podcastId = "never"),
                episode("gone-1", positionMs = 1, lastPlayedAt = 999).copy(podcastId = "gone"),
            )
        )

        val shows = podcasts.subscribedWithLastPlayed().first().associate { it.podcast.id to it.lastPlayedAt }
        assertEquals(mapOf("heard" to 900L, "never" to 0L), shows)
    }

    private fun podcast(id: String, subscribed: Boolean) = PodcastEntity(
        id = id,
        title = id,
        author = "",
        feedUrl = "https://example.com/$id.xml",
        artworkUrl = null,
        description = null,
        subscribed = subscribed,
    )

    private fun episode(
        id: String,
        positionMs: Long,
        lastPlayedAt: Long,
        completed: Boolean = false,
        downloadStatus: DownloadStatus = DownloadStatus.NONE,
        autoDownloadBlocked: Boolean = false,
    ) = EpisodeEntity(
        id = id,
        podcastId = "podcast",
        podcastTitle = "Podcast",
        guid = id,
        title = id,
        description = null,
        audioUrl = "https://example.com/$id.mp3",
        pubDateMs = 0,
        durationMs = null,
        artworkUrl = null,
        downloadStatus = downloadStatus,
        autoDownloadBlocked = autoDownloadBlocked,
        playbackPositionMs = positionMs,
        completed = completed,
        lastPlayedAt = lastPlayedAt,
    )
}
