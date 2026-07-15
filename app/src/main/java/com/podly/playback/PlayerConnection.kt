package com.podly.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

data class QueueItem(
    val episodeId: String,
    val title: String?,
    val podcastTitle: String?,
    val artworkUri: String?,
)

data class PlayerUiState(
    val episodeId: String? = null,
    val title: String? = null,
    val podcastTitle: String? = null,
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val seekBackSeconds: Int = 10,
    val seekForwardSeconds: Int = 30,
    val queue: List<QueueItem> = emptyList(),
    val queueIndex: Int = 0,
    val hasNextEpisode: Boolean = false,
    val hasPreviousEpisode: Boolean = false,
    /** Set while the player is in a failed state; pressing play retries. */
    val errorMessage: String? = null,
)

/**
 * UI-side handle on the playback service. One instance lives in AppGraph;
 * composables collect [state] and poll [positionMs] while visible.
 */
class PlayerConnection(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    init {
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val mediaController = MediaController.Builder(context, token).buildAsync().await()
            controller = mediaController
            mediaController.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) =
                    syncState(player, events)
            })
            syncState(mediaController)
        }
    }

    private fun syncState(player: Player, events: Player.Events? = null) {
        val metadata: MediaMetadata? = player.currentMediaItem?.mediaMetadata
        // Rebuilding the queue list is only needed when the timeline itself changes.
        val queue = if (events == null || events.contains(Player.EVENT_TIMELINE_CHANGED)) {
            (0 until player.mediaItemCount).mapNotNull { index ->
                val item = player.getMediaItemAt(index)
                val episodeId = MediaIds.episodeIdOrNull(item.mediaId) ?: return@mapNotNull null
                QueueItem(
                    episodeId = episodeId,
                    title = item.mediaMetadata.title?.toString(),
                    podcastTitle = item.mediaMetadata.artist?.toString(),
                    artworkUri = item.mediaMetadata.artworkUri?.toString(),
                )
            }
        } else {
            _state.value.queue
        }
        _state.value = _state.value.copy(
            episodeId = player.currentMediaItem?.mediaId?.let(MediaIds::episodeIdOrNull),
            title = metadata?.title?.toString(),
            podcastTitle = metadata?.artist?.toString(),
            artworkUri = metadata?.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.coerceAtLeast(0),
            speed = player.playbackParameters.speed,
            queue = queue,
            queueIndex = player.currentMediaItemIndex,
            hasNextEpisode = player.hasNextMediaItem(),
            hasPreviousEpisode = player.hasPreviousMediaItem(),
            errorMessage = player.playerError?.let { error ->
                "Playback failed: ${error.message ?: error.errorCodeName}"
            },
        )
    }

    fun positionMs(): Long = controller?.currentPosition ?: 0

    /** Queue the given episodes and start playback at [startIndex]. */
    fun play(episodeIds: List<String>, startIndex: Int = 0) {
        val mediaController = controller ?: return
        val items = episodeIds.map { MediaItem.Builder().setMediaId(MediaIds.episode(it)).build() }
        mediaController.setMediaItems(items, startIndex, androidx.media3.common.C.TIME_UNSET)
        mediaController.prepare()
        mediaController.play()
    }

    fun playSingle(episodeId: String) = play(listOf(episodeId))

    fun togglePlayPause() {
        val mediaController = controller ?: return
        if (mediaController.isPlaying) {
            mediaController.pause()
        } else {
            // After a playback error the player sits in IDLE; prepare() retries the source.
            if (mediaController.playbackState == Player.STATE_IDLE) mediaController.prepare()
            mediaController.play()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun seekBack() {
        controller?.seekBack()
    }

    fun seekForward() {
        controller?.seekForward()
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
    }

    /** Jumps to the next episode in the queue (unlike car buttons, which nudge). */
    fun nextEpisode() {
        controller?.seekToNextMediaItem()
    }

    fun previousEpisode() {
        controller?.seekToPreviousMediaItem()
    }

    /** Jumps to the queue item at [index]; PlaybackService restores its saved position. */
    fun playQueueItem(index: Int) {
        val mediaController = controller ?: return
        if (index !in 0 until mediaController.mediaItemCount) return
        if (index != mediaController.currentMediaItemIndex) {
            mediaController.seekToDefaultPosition(index)
        }
        mediaController.play()
    }
}
