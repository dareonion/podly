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
                override fun onEvents(player: Player, events: Player.Events) = syncState(player)
            })
            syncState(mediaController)
        }
    }

    private fun syncState(player: Player) {
        val metadata: MediaMetadata? = player.currentMediaItem?.mediaMetadata
        _state.value = _state.value.copy(
            episodeId = player.currentMediaItem?.mediaId?.let(MediaIds::episodeIdOrNull),
            title = metadata?.title?.toString(),
            podcastTitle = metadata?.artist?.toString(),
            artworkUri = metadata?.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.coerceAtLeast(0),
            speed = player.playbackParameters.speed,
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
        if (mediaController.isPlaying) mediaController.pause() else mediaController.play()
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
}
