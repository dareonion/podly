package com.podly.playback

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podly.data.PlaylistRepository
import com.podly.data.db.DownloadStatus
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PlaylistEntity
import com.podly.data.db.PodcastEntity
import com.podly.data.db.PodcastEpisodeSortOrder
import com.podly.data.db.PodlyDatabase
import com.podly.data.db.RadioPoolEntity
import com.podly.radio.RadioProfiles
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Plain Application: PodlyApp's onCreate spins up WorkManager, which these tests don't need.
@Config(application = Application::class)
@RunWith(AndroidJUnit4::class)
class CarBrowseTreeTest {

    private lateinit var db: PodlyDatabase
    private lateinit var tree: CarBrowseTree

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            PodlyDatabase::class.java,
        ).allowMainThreadQueries().build()
        tree = CarBrowseTree(db, PlaylistRepository(db.playlistDao())) { Collator.getInstance(Locale.US) }
    }

    @After
    fun closeDb() = db.close()

    private fun children(parentId: String) = runBlocking { tree.children(parentId) }
    private fun ids(parentId: String) = children(parentId).map { it.mediaId }

    @Test
    fun rootHoldsAtMostFourTabsWithPodcastsAmongThem() = runBlocking {
        assertEquals(
            listOf(MediaIds.NODE_CONTINUE, MediaIds.NODE_PODCASTS, MediaIds.NODE_LIBRARY),
            ids(MediaIds.ROOT),
        )

        // A synced digest adds This week, and the root is then at Android Auto's limit.
        db.podcastDao().insertIgnore(podcast("show"))
        db.episodeDao().insertIgnore(episode("weekly-pick", "show"))
        db.radioDao().upsertPool(listOf(RadioPoolEntity(profileId = RadioProfiles.WEEKLY_ID, episodeId = "weekly-pick")))
        val root = children(MediaIds.ROOT)
        assertEquals(
            listOf(MediaIds.NODE_CONTINUE, MediaIds.NODE_WEEKLY, MediaIds.NODE_PODCASTS, MediaIds.NODE_LIBRARY),
            root.map { it.mediaId },
        )
        // Android Auto only turns browsable root items into tabs.
        assertTrue(root.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
    }

    @Test
    fun libraryHoldsPlaylistsDownloadsAndAllEpisodes() {
        val library = children(MediaIds.NODE_LIBRARY)
        assertEquals(
            listOf(MediaIds.NODE_PLAYLISTS, MediaIds.NODE_DOWNLOADS, MediaIds.NODE_ALL_EPISODES),
            library.map { it.mediaId },
        )
        assertEquals(listOf("Playlists", "Downloads", "All episodes"), library.map { it.mediaMetadata.title.toString() })
        assertTrue(library.all { it.mediaMetadata.isBrowsable == true })
    }

    @Test
    fun podcastsListsSubscribedShowsRecentlyPlayedFirstThenByName() = runBlocking {
        val podcasts = db.podcastDao()
        podcasts.insertIgnore(podcast("zebra", title = "Zebra Talk"))
        podcasts.insertIgnore(podcast("apple", title = "Apple Hour"))
        podcasts.insertIgnore(podcast("mango", title = "Mango Daily"))
        podcasts.insertIgnore(podcast("gone", title = "Aardvark", subscribed = false))
        db.episodeDao().insertIgnore(
            listOf(
                episode("z1", "zebra", lastPlayedAt = 500),
                episode("m1", "mango", lastPlayedAt = 100),
                episode("m2", "mango", lastPlayedAt = 900),
                episode("g1", "gone", lastPlayedAt = 999),
            )
        )

        val shows = children(MediaIds.NODE_PODCASTS)
        assertEquals(
            listOf(MediaIds.podcast("mango"), MediaIds.podcast("zebra"), MediaIds.podcast("apple")),
            shows.map { it.mediaId },
        )
        val first = shows.first().mediaMetadata
        assertEquals("Mango Daily", first.title.toString())
        assertEquals("https://example.com/mango.jpg", first.artworkUri.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_PODCAST, first.mediaType)
        assertTrue(first.isBrowsable == true)
        assertFalse(first.isPlayable == true)
    }

    @Test
    fun aShowOpensToItsEpisodesInItsOwnOrderCappedAtTheLimit() = runBlocking {
        db.podcastDao().insertIgnore(podcast("news"))
        db.podcastDao().insertIgnore(podcast("serial", sortOrder = PodcastEpisodeSortOrder.OLDEST_FIRST))
        val many = CarBrowseTree.MAX_CHILDREN + 20
        db.episodeDao().insertIgnore((1..many).map { episode("news-$it", "news", pubDateMs = it.toLong()) })
        db.episodeDao().insertIgnore((1..3).map { episode("serial-$it", "serial", pubDateMs = it.toLong()) })

        val news = children(MediaIds.podcast("news"))
        assertEquals(CarBrowseTree.MAX_CHILDREN, news.size)
        assertEquals(MediaIds.episode("news-$many"), news.first().mediaId)
        assertTrue(news.all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable == false })

        assertEquals(
            listOf("serial-1", "serial-2", "serial-3").map(MediaIds::episode),
            ids(MediaIds.podcast("serial")),
        )
    }

    @Test
    fun movedNodesStillServeTheirEpisodes() = runBlocking {
        db.podcastDao().insertIgnore(podcast("show"))
        db.episodeDao().insertIgnore(
            listOf(
                episode("downloaded", "show", pubDateMs = 2).copy(downloadStatus = DownloadStatus.DONE),
                episode("streamed", "show", pubDateMs = 1),
            )
        )
        val playlistId = db.playlistDao().insert(PlaylistEntity(name = "Road trip"))
        db.playlistDao().addToEnd(playlistId, "streamed")

        assertEquals(listOf(MediaIds.episode("downloaded")), ids(MediaIds.NODE_DOWNLOADS))
        assertEquals(listOf("downloaded", "streamed").map(MediaIds::episode), ids(MediaIds.NODE_ALL_EPISODES))
        val playlists = children(MediaIds.NODE_PLAYLISTS)
        assertEquals(listOf(MediaIds.playlist(playlistId)), playlists.map(MediaItem::mediaId))
        assertEquals(listOf(MediaIds.episode("streamed")), ids(MediaIds.playlist(playlistId)))
    }

    @Test
    fun unknownParentsAreEmpty() {
        assertEquals(emptyList<String>(), ids("node/nope"))
        assertEquals(emptyList<String>(), ids(MediaIds.podcast("no-such-show")))
        assertEquals(emptyList<String>(), ids(MediaIds.PODCAST_PREFIX))
    }

    private fun podcast(
        id: String,
        title: String = id,
        subscribed: Boolean = true,
        sortOrder: PodcastEpisodeSortOrder = PodcastEpisodeSortOrder.NEWEST_FIRST,
    ) = PodcastEntity(
        id = id,
        title = title,
        author = "Author",
        feedUrl = "https://example.com/$id.xml",
        artworkUrl = "https://example.com/$id.jpg",
        description = null,
        subscribed = subscribed,
        episodeSortOrder = sortOrder,
    )

    private fun episode(id: String, podcastId: String, pubDateMs: Long = 0, lastPlayedAt: Long = 0) = EpisodeEntity(
        id = id,
        podcastId = podcastId,
        podcastTitle = podcastId,
        guid = id,
        title = id,
        description = null,
        audioUrl = "https://example.com/$id.mp3",
        pubDateMs = pubDateMs,
        durationMs = null,
        artworkUrl = null,
        lastPlayedAt = lastPlayedAt,
    )
}
