package com.podly.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.podly.data.db.DownloadStatus
import com.podly.data.db.EpisodeEntity
import java.io.File

object MediaIds {
    const val ROOT = "root"
    const val NODE_CONTINUE = "node/continue"
    const val NODE_PLAYLISTS = "node/playlists"
    const val NODE_LIBRARY = "node/library"
    const val NODE_DOWNLOADS = "node/downloads"
    const val PLAYLIST_PREFIX = "playlist/"
    const val EPISODE_PREFIX = "ep/"

    fun episode(id: String) = "$EPISODE_PREFIX$id"
    fun playlist(id: Long) = "$PLAYLIST_PREFIX$id"
    fun episodeIdOrNull(mediaId: String): String? =
        mediaId.takeIf { it.startsWith(EPISODE_PREFIX) }?.removePrefix(EPISODE_PREFIX)
    fun playlistIdOrNull(mediaId: String): Long? =
        mediaId.takeIf { it.startsWith(PLAYLIST_PREFIX) }?.removePrefix(PLAYLIST_PREFIX)?.toLongOrNull()
}

object MediaItemFactory {

    /**
     * Playable item carrying the resolved URI (local file when downloaded, else stream).
     *
     * With [forCast] the local file is deliberately skipped: a Cast device fetches
     * the audio over the network itself and cannot reach the app's private download
     * directory, so casting always streams the original enclosure URL.
     */
    fun playable(episode: EpisodeEntity, forCast: Boolean = false): MediaItem {
        val uri = when {
            forCast -> episode.audioUrl.toUri()
            else -> localUriOrNull(episode) ?: episode.audioUrl.toUri()
        }
        return MediaItem.Builder()
            .setMediaId(MediaIds.episode(episode.id))
            .setUri(uri)
            // Cast's DefaultMediaItemConverter requires a MIME type; locally this
            // matches what ExoPlayer would infer from the URL anyway.
            .setMimeType(audioMimeType(episode.audioUrl))
            .setMediaMetadata(metadata(episode, isPlayable = true))
            .build()
    }

    /** Best-effort MIME type from the enclosure URL, defaulting to MP3. */
    fun audioMimeType(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m4a") || path.endsWith(".mp4") || path.endsWith(".m4b") -> "audio/mp4"
            path.endsWith(".aac") -> "audio/aac"
            path.endsWith(".opus") -> "audio/opus"
            path.endsWith(".ogg") || path.endsWith(".oga") -> "audio/ogg"
            path.endsWith(".wav") -> "audio/wav"
            path.endsWith(".flac") -> "audio/flac"
            else -> "audio/mpeg"
        }
    }

    /** Metadata-only item (no URI) as served to browsers like Android Auto. */
    fun browsableEpisode(episode: EpisodeEntity): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaIds.episode(episode.id))
            .setMediaMetadata(metadata(episode, isPlayable = true))
            .build()

    fun folder(mediaId: String, title: String, childrenAreEpisodes: Boolean = true): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(
                        if (childrenAreEpisodes) MediaMetadata.MEDIA_TYPE_PLAYLIST
                        else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    )
                    .build()
            )
            .build()

    private fun metadata(episode: EpisodeEntity, isPlayable: Boolean): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(episode.podcastTitle)
            .setAlbumTitle(episode.podcastTitle)
            .setArtworkUri(episode.artworkUrl?.toUri())
            .setIsBrowsable(false)
            .setIsPlayable(isPlayable)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .build()

    fun localUriOrNull(episode: EpisodeEntity): Uri? {
        if (episode.downloadStatus != DownloadStatus.DONE) return null
        val path = episode.localFilePath ?: return null
        val file = File(path)
        return if (file.exists()) Uri.fromFile(file) else null
    }
}
