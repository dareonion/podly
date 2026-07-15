package com.podly.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.PositionInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
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
import com.podly.data.db.ListeningSegmentEntity
import com.podly.network.Http
import com.podly.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private var activeListenSegment: ActiveListenSegment? = null

    /** Episode whose saved position is about to be restored; saveProgress must not clobber it. */
    private var pendingResumeEpisodeId: String? = null

    override fun onCreate() {
        super.onCreate()
        val graph = appGraph

        val httpDataSourceFactory = OkHttpDataSource.Factory(Http.client)
        // Streamed audio goes through the LRU cache; file:// URIs (downloads) bypass it
        // because DefaultDataSource only delegates http(s) to this factory.
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(MediaCache.get(this))
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = DefaultDataSource.Factory(this, cacheDataSourceFactory)

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
            .build()

        val player = NudgingPlayer(exoPlayer)
        // Nudge increments track Settings live (no runBlocking, no service restart).
        scope.launch {
            graph.settings.settings.collect { settings ->
                player.seekBackMs = settings.seekBackSeconds * 1000L
                player.seekForwardMs = settings.seekForwardSeconds * 1000L
            }
        }
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(mainActivityIntent())
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
        // Capture the in-flight segment synchronously; the insert outlives this
        // service on the app-wide scope instead of blocking the main thread.
        takePendingSegment()?.let { segment ->
            appGraph.applicationScope.launch {
                appGraph.database.episodeDao().insertListeningSegment(segment)
            }
        }
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun mainActivityIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Remaps next/previous (car & headset buttons) to in-episode nudges.
     * seekToNext/PreviousMediaItem stay untouched so the in-app UI can still
     * move through the queue. Increments are mutable so Settings changes apply
     * to the running player.
     */
    private class NudgingPlayer(player: Player) : ForwardingPlayer(player) {
        @Volatile var seekBackMs: Long = 10_000
        @Volatile var seekForwardMs: Long = 30_000

        override fun seekToNext() = seekForward()
        override fun seekToPrevious() = seekBack()

        override fun seekBack() = seekTo((currentPosition - seekBackMs).coerceAtLeast(0))

        override fun seekForward() {
            val target = currentPosition + seekForwardMs
            val duration = duration
            seekTo(if (duration == C.TIME_UNSET) target else target.coerceAtMost(duration))
        }

        override fun getSeekBackIncrement(): Long = seekBackMs

        override fun getSeekForwardIncrement(): Long = seekForwardMs

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addAll(
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                )
                .build()

        override fun isCommandAvailable(command: Int): Boolean =
            when (command) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                -> true
                else -> super.isCommandAvailable(command)
            }
    }

    /** Persists playback position every few seconds so episodes resume where they left off. */
    private fun startProgressPersistence(player: Player) {
        scope.launch {
            while (isActive) {
                delay(5_000)
                recordListeningProgress(player)
                saveProgress(player)
            }
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                scope.launch {
                    if (isPlaying) {
                        beginListeningSegment(player)
                    } else {
                        recordListeningProgress(player)
                        flushListeningSegment()
                        saveProgress(player)
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Auto-advance and in-queue jumps start episodes at 0; restore the saved
                // position instead. The initial queue set is handled by onSetMediaItems.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                ) {
                    mediaItem?.mediaId?.let(MediaIds::episodeIdOrNull)?.let { episodeId ->
                        pendingResumeEpisodeId = episodeId
                        scope.launch { resumeSavedPosition(player, episodeId) }
                    }
                }
                scope.launch {
                    flushListeningSegment()
                    if (player.isPlaying) beginListeningSegment(player)
                    snapshotQueue(player)
                    // Frees space right after an episode finishes (no-op unless enabled).
                    appGraph.downloader.deleteCompletedDownloads()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: PositionInfo,
                newPosition: PositionInfo,
                reason: Int,
            ) {
                scope.launch {
                    flushListeningSegment()
                    if (player.isPlaying) beginListeningSegment(player)
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                scope.launch { snapshotQueue(player) }
            }
        })
    }

    private fun beginListeningSegment(player: Player) {
        val episodeId = player.currentEpisodeIdOrNull() ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        activeListenSegment = ActiveListenSegment(
            episodeId = episodeId,
            startPositionMs = position,
            endPositionMs = position,
            startedAt = now,
            endedAt = now,
        )
    }

    private suspend fun recordListeningProgress(player: Player) {
        if (!player.isPlaying) return
        val episodeId = player.currentEpisodeIdOrNull() ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val active = activeListenSegment
        if (active == null || active.episodeId != episodeId || !active.canContinueTo(position)) {
            flushListeningSegment()
            activeListenSegment = ActiveListenSegment(
                episodeId = episodeId,
                startPositionMs = position,
                endPositionMs = position,
                startedAt = now,
                endedAt = now,
            )
        } else {
            activeListenSegment = active.copy(
                endPositionMs = position.coerceAtLeast(active.endPositionMs),
                endedAt = now,
            )
        }
    }

    /** Detaches the active segment, or null if there's nothing worth persisting. */
    private fun takePendingSegment(): ListeningSegmentEntity? {
        val active = activeListenSegment ?: return null
        activeListenSegment = null
        if (active.endPositionMs - active.startPositionMs < MIN_LISTEN_SEGMENT_MS) return null
        return ListeningSegmentEntity(
            episodeId = active.episodeId,
            startPositionMs = active.startPositionMs,
            endPositionMs = active.endPositionMs,
            startedAt = active.startedAt,
            endedAt = active.endedAt,
        )
    }

    private suspend fun flushListeningSegment() {
        takePendingSegment()?.let { appGraph.database.episodeDao().insertListeningSegment(it) }
    }

    private fun Player.currentEpisodeIdOrNull(): String? =
        currentMediaItem?.mediaId?.let(MediaIds::episodeIdOrNull)

    private suspend fun resumeSavedPosition(player: Player, episodeId: String) {
        try {
            val episode = appGraph.database.episodeDao().byId(episodeId)
            if (player.currentEpisodeIdOrNull() == episodeId &&
                episode != null && !episode.completed && episode.playbackPositionMs > 0
            ) {
                player.seekTo(episode.playbackPositionMs)
            }
        } finally {
            if (pendingResumeEpisodeId == episodeId) pendingResumeEpisodeId = null
        }
    }

    /** Remembers the queue so onPlaybackResumption can restore it after process death. */
    private suspend fun snapshotQueue(player: Player) {
        val ids = (0 until player.mediaItemCount).mapNotNull { index ->
            MediaIds.episodeIdOrNull(player.getMediaItemAt(index).mediaId)
        }
        if (ids.isNotEmpty()) {
            appGraph.playbackState.save(ids, player.currentMediaItemIndex)
        }
    }

    private suspend fun saveProgress(player: Player) {
        if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_ENDED) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val episodeId = MediaIds.episodeIdOrNull(mediaId) ?: return
        // A queue transition is still restoring this episode's saved position;
        // saving now would overwrite it with ~0.
        if (episodeId == pendingResumeEpisodeId) return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val completed = duration > 0 && position >= duration - 10_000
        val dao = appGraph.database.episodeDao()
        dao.updateProgress(episodeId, if (completed) 0 else position, completed, System.currentTimeMillis())
        if (duration > 0) dao.updateDuration(episodeId, duration)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            scope.future(Dispatchers.IO) {
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
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val graph = appGraph
            val children: List<MediaItem> = when {
                parentId == MediaIds.ROOT -> listOf(
                    MediaItemFactory.folder(MediaIds.NODE_CONTINUE, "Continue"),
                    MediaItemFactory.folder(MediaIds.NODE_PLAYLISTS, "Playlists", childrenAreEpisodes = false),
                    MediaItemFactory.folder(MediaIds.NODE_LIBRARY, "Library"),
                    MediaItemFactory.folder(MediaIds.NODE_DOWNLOADS, "Downloads"),
                )
                parentId == MediaIds.NODE_CONTINUE ->
                    graph.database.episodeDao().continueListeningOnce(MAX_BROWSE_CHILDREN)
                        .map(MediaItemFactory::browsableEpisode)
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
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
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
        ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
            mediaItems.mapNotNull { item -> resolve(item) }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
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

        /**
         * Android Auto / system "resume" entry point after process death: restore the
         * last queue and position, falling back to the most recent in-progress episode.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val graph = appGraph
            val saved = graph.playbackState.load()
            val episodes: List<EpisodeEntity>
            val startIndex: Int
            if (saved != null) {
                val byId = saved.episodeIds
                    .mapNotNull { graph.podcasts.episodeById(it) }
                    .associateBy { it.id }
                episodes = saved.episodeIds.mapNotNull(byId::get)
                val savedEpisodeId = saved.episodeIds.getOrNull(saved.currentIndex)
                startIndex = episodes.indexOfFirst { it.id == savedEpisodeId }.coerceAtLeast(0)
            } else {
                episodes = graph.database.episodeDao().continueListeningOnce(1)
                    .ifEmpty { graph.database.episodeDao().libraryEpisodesOnce().take(1) }
                startIndex = 0
            }
            if (episodes.isEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            }
            val current = episodes[startIndex]
            val resumePosition = current.playbackPositionMs.takeIf { !current.completed } ?: 0L
            MediaSession.MediaItemsWithStartPosition(
                episodes.map(MediaItemFactory::playable),
                startIndex,
                resumePosition,
            )
        }
    }

    companion object {
        private const val MAX_BROWSE_CHILDREN = 100
        private const val MIN_LISTEN_SEGMENT_MS = 1_000L
        private const val CONTINUOUS_POSITION_TOLERANCE_MS = 12_000L
    }

    private data class ActiveListenSegment(
        val episodeId: String,
        val startPositionMs: Long,
        val endPositionMs: Long,
        val startedAt: Long,
        val endedAt: Long,
    ) {
        fun canContinueTo(positionMs: Long): Boolean =
            positionMs >= endPositionMs &&
                positionMs - endPositionMs <= CONTINUOUS_POSITION_TOLERANCE_MS
    }
}
