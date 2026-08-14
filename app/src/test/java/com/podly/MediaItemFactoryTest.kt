package com.podly

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podly.data.db.DownloadStatus
import com.podly.data.db.EpisodeEntity
import com.podly.playback.MediaItemFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Plain Application: PodlyApp's onCreate spins up WorkManager, which these tests don't need.
@Config(application = Application::class)
@RunWith(AndroidJUnit4::class)
class MediaItemFactoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun castItemStreamsEvenWhenTheEpisodeIsDownloaded() {
        val file = temporaryFolder.newFile("episode.mp3")
        val episode = episode(localFilePath = file.absolutePath, status = DownloadStatus.DONE)

        // Sanity: local playback really does prefer the downloaded file.
        val local = MediaItemFactory.playable(episode)
        assertEquals("file", local.localConfiguration?.uri?.scheme)

        // A Cast device fetches the audio itself and cannot read app-private
        // storage, so the cast item must point at the original URL.
        val cast = MediaItemFactory.playable(episode, forCast = true)
        assertEquals(episode.audioUrl, cast.localConfiguration?.uri?.toString())
    }

    @Test
    fun playableAlwaysCarriesAMimeTypeForTheCastConverter() {
        val item = MediaItemFactory.playable(episode(), forCast = true)
        // DefaultMediaItemConverter throws without one.
        assertNotNull(item.localConfiguration?.mimeType)
        assertTrue(item.localConfiguration!!.mimeType!!.startsWith("audio/"))
    }

    @Test
    fun audioMimeTypeIsInferredFromTheUrlWithMp3Default() {
        assertEquals("audio/mpeg", MediaItemFactory.audioMimeType("https://x.test/a.mp3"))
        assertEquals("audio/mp4", MediaItemFactory.audioMimeType("https://x.test/a.m4a"))
        assertEquals("audio/ogg", MediaItemFactory.audioMimeType("https://x.test/a.ogg"))
        // Query strings and fragments must not defeat the extension check.
        assertEquals("audio/mp4", MediaItemFactory.audioMimeType("https://x.test/a.m4a?token=1"))
        // Extensionless CDN URLs are common; MP3 is the safe podcast default.
        assertEquals("audio/mpeg", MediaItemFactory.audioMimeType("https://x.test/stream/12345"))
    }

    @Test
    fun localUriIsSkippedWhenTheDownloadedFileIsMissing() {
        val episode = episode(localFilePath = "/does/not/exist.mp3", status = DownloadStatus.DONE)
        val item = MediaItemFactory.playable(episode)
        assertEquals(episode.audioUrl, item.localConfiguration?.uri?.toString())
    }

    private fun episode(
        localFilePath: String? = null,
        status: DownloadStatus = DownloadStatus.NONE,
    ) = EpisodeEntity(
        id = "ep1",
        podcastId = "p1",
        podcastTitle = "Podcast",
        guid = "guid",
        title = "Episode",
        description = null,
        audioUrl = "https://example.test/episode.mp3",
        pubDateMs = 0,
        durationMs = null,
        artworkUrl = null,
        downloadStatus = status,
        localFilePath = localFilePath,
    )
}
