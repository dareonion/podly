package com.podly

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podly.data.PlaylistRepository
import com.podly.data.db.EpisodeEntity
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
class PlaylistNotesTest {

    private lateinit var db: PodlyDatabase
    private lateinit var playlists: PlaylistRepository

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            PodlyDatabase::class.java,
        ).allowMainThreadQueries().build()
        playlists = PlaylistRepository(db.playlistDao())
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun notesSurviveAManualReorder() = runBlocking {
        db.episodeDao().insertIgnore(listOf(episode("a"), episode("b"), episode("c")))
        val id = playlists.create("Picks")
        playlists.addEpisode(id, "a", "A woman from Kaifeng explores her Jewish heritage.")
        playlists.addEpisode(id, "b", null)
        playlists.addEpisode(id, "c", "  ")

        playlists.reorder(id, listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), playlists.sortedEpisodesOnce(id).map { it.id })
        assertEquals(
            mapOf("a" to "A woman from Kaifeng explores her Jewish heritage."),
            playlists.notes(id).first(),
        )
    }

    private fun episode(id: String) = EpisodeEntity(
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
    )
}
