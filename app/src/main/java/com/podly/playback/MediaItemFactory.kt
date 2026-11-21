package com.podly.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.podly.data.db.DownloadStatus
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PodcastEntity
import java.io.File

object MediaIds {
    const val ROOT = "root"
    const val NODE_CONTINUE = "node/continue"
    const val NODE_PLAYLISTS = "node/playlists"
    const val NODE_LIBRARY = "node/library"
    const val NODE_ALL_EPISODES = "node/library/episodes"
    const val NODE_DOWNLOADS = "node/downloads"
    const val NODE_WEEKLY = "node/weekly"
    const val NODE_PODCASTS = "node/podcasts"
    const val PLAYLIST_PREFIX = "playlist/"
    const val PODCAST_PREFIX = "podcast/"
    const val EPISODE_PREFIX = "ep/"
    /** An episode browsed from inside its show: playing it plays on through the show. */
    const val SHOW_EPISODE_PREFIX = "show-ep/"

    fun episode(id: String) = "$EPISODE_PREFIX$id"
    fun playlist(id: Long) = "$PLAYLIST_PREFIX$id"
    fun podcast(id: String) = "$PODCAST_PREFIX$id"
    fun showEpisode(episodeId: String) = "$SHOW_EPISODE_PREFIX$episodeId"

    /** The episode either kind of episode id names. */
    fun episodeIdOrNull(mediaId: String): String? =
        mediaId.takeIf { it.startsWith(EPISODE_PREFIX) }?.removePrefix(EPISODE_PREFIX)
            ?: showEpisodeIdOrNull(mediaId)

    fun showEpisodeIdOrNull(mediaId: String): String? =
        mediaId.takeIf { it.startsWith(SHOW_EPISODE_PREFIX) }?.removePrefix(SHOW_EPISODE_PREFIX)
    fun playlistIdOrNull(mediaId: String): Long? =
        mediaId.takeIf { it.startsWith(PLAYLIST_PREFIX) }?.removePrefix(PLAYLIST_PREFIX)?.toLongOrNull()
    fun podcastIdOrNull(mediaId: String): String? =
        mediaId.takeIf { it.startsWith(PODCAST_PREFIX) }?.removePrefix(PODCAST_PREFIX)?.takeIf { it.isNotEmpty() }
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
            // The stream, even when the URI is the download: whatever reaches the Cast
            // player swaps a file:// URI back to it there (castPlayable).
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setMediaUri(episode.audioUrl.toUri()).build()
            )
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

    /**
     * Metadata-only item (no URI) as served to browsers like Android Auto. [mediaId] is
     * the plain episode id unless the item should play on through its show.
     */
    fun browsableEpisode(episode: EpisodeEntity, mediaId: String = MediaIds.episode(episode.id)): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata(episode, isPlayable = true))
            .build()

    /** A browse-only node; [mediaType] says what it holds (the default: a list of episodes). */
    fun folder(
        mediaId: String,
        title: String,
        mediaType: @MediaMetadata.MediaType Int = MediaMetadata.MEDIA_TYPE_PLAYLIST,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()

    /** A show to browse into: its children are its episodes. */
    fun podcast(podcast: PodcastEntity): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaIds.podcast(podcast.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(podcast.title)
                    .setArtist(podcast.author.takeIf { it.isNotBlank() })
                    .setArtworkUri(podcast.artworkUrl?.toUri())
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
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
