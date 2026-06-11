package com.podly.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.podly.appGraph
import com.podly.data.db.EpisodeEntity
import com.podly.network.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Media3 library service used both by the in-app player UI and Android Auto.
 *
 * Car/headset "previous" and "next" buttons are remapped to small in-episode
 * jumps (seekBack/seekForward) via [NudgingPlayer].
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val graph = appGraph
        val settings = runBlocking { graph.settings.current() }

        val httpDataSourceFactory = OkHttpDataSource.Factory(Http.client)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(settings.seekBackSeconds * 1000L)
            .setSeekForwardIncrementMs(settings.seekForwardSeconds * 1000L)
            .build()

        val player = NudgingPlayer(exoPlayer)
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .build()

        startProgressPersistence(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Remaps next/previous (car & headset buttons) to in-episode nudges. */
    private class NudgingPlayer(player: Player) : ForwardingPlayer(player) {
        override fun seekToNext() = seekForward()
        override fun seekToNextMediaItem() = seekForward()
        override fun seekToPrevious() = seekBack()
        override fun seekToPreviousMediaItem() = seekBack()

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addAll(
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
                .build()

        override fun isCommandAvailable(command: Int): Boolean =
            when (command) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                -> true
                else -> super.isCommandAvailable(command)
            }
    }

    /** Persists playback position every few seconds so episodes resume where they left off. */
    private fun startProgressPersistence(player: Player) {
        scope.launch {
            while (isActive) {
                delay(5_000)
                saveProgress(player)
            }
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) scope.launch { saveProgress(player) }
            }
        })
    }

    private suspend fun saveProgress(player: Player) {
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_ENDED) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val episodeId = MediaIds.episodeIdOrNull(mediaId) ?: return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val completed = duration > 0 && position >= duration - 10_000
        val dao = appGraph.database.episodeDao()
        dao.updateProgress(episodeId, if (completed) 0 else position, completed)
        if (duration > 0) dao.updateDuration(episodeId, duration)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            scope.future {
                LibraryResult.ofItem(
                    MediaItemFactory.folder(MediaIds.ROOT, "Podly", childrenAreEpisodes = false),
                    params,
                )
            }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val graph = appGraph
            val children: List<MediaItem> = when {
                parentId == MediaIds.ROOT -> listOf(
                    MediaItemFactory.folder(MediaIds.NODE_PLAYLISTS, "Playlists", childrenAreEpisodes = false),
                    MediaItemFactory.folder(MediaIds.NODE_LIBRARY, "Library"),
                    MediaItemFactory.folder(MediaIds.NODE_DOWNLOADS, "Downloads"),
                )
                parentId == MediaIds.NODE_PLAYLISTS ->
                    graph.database.playlistDao().playlistsOnce().map { playlist ->
                        MediaItemFactory.folder(MediaIds.playlist(playlist.id), playlist.name)
                    }
                parentId == MediaIds.NODE_LIBRARY ->
                    graph.database.episodeDao().libraryEpisodesOnce()
                        .take(MAX_BROWSE_CHILDREN)
                        .map(MediaItemFactory::browsableEpisode)
                parentId == MediaIds.NODE_DOWNLOADS ->
                    graph.database.episodeDao().downloadedEpisodesOnce()
                        .map(MediaItemFactory::browsableEpisode)
                else -> MediaIds.playlistIdOrNull(parentId)?.let { playlistId ->
                    graph.playlists.sortedEpisodesOnce(playlistId)
                        .map(MediaItemFactory::browsableEpisode)
                } ?: emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val episodeId = MediaIds.episodeIdOrNull(mediaId)
            val episode = episodeId?.let { appGraph.podcasts.episodeById(it) }
            if (episode != null) {
                LibraryResult.ofItem(MediaItemFactory.browsableEpisode(episode), null)
            } else {
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
        }

        /** Resolves bare mediaIds (from Auto or the in-app UI) into playable items with URIs. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future {
            mediaItems.mapNotNull { item -> resolve(item) }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val resolvedPairs = mediaItems.mapNotNull { item ->
                val episodeId = MediaIds.episodeIdOrNull(item.mediaId)
                val episode = episodeId?.let { appGraph.podcasts.episodeById(it) }
                if (episode != null) MediaItemFactory.playable(episode) to episode else null
            }
            val resolved = resolvedPairs.map { it.first }
            val safeIndex = startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
            val resumePosition = when {
                startPositionMs != C.TIME_UNSET && startPositionMs > 0 -> startPositionMs
                else -> resolvedPairs.getOrNull(safeIndex)?.second
                    ?.takeIf { !it.completed }?.playbackPositionMs ?: 0L
            }
            MediaSession.MediaItemsWithStartPosition(resolved, safeIndex, resumePosition)
        }

        private suspend fun resolve(item: MediaItem): MediaItem? {
            val episodeId = MediaIds.episodeIdOrNull(item.mediaId) ?: return null
            val episode: EpisodeEntity = appGraph.podcasts.episodeById(episodeId) ?: return null
            return MediaItemFactory.playable(episode)
        }
    }

    companion object {
        private const val MAX_BROWSE_CHILDREN = 100
    }
}
