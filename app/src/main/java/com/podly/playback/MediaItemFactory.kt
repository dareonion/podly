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

    /** Playable item carrying the resolved URI (local file when downloaded, else stream). */
    fun playable(episode: EpisodeEntity): MediaItem {
        val uri = localUriOrNull(episode) ?: episode.audioUrl.toUri()
        return MediaItem.Builder()
            .setMediaId(MediaIds.episode(episode.id))
            .setUri(uri)
            .setMediaMetadata(metadata(episode, isPlayable = true))
            .build()
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
