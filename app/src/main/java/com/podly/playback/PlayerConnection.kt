package com.podly.playback

import android.content.ComponentName
import android.os.Bundle
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.podly.ui.util.friendlyError
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
    /** Non-null while a radio session owns the queue; drives the skip affordances. */
    val radioProfileId: String? = null,
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

    /** Timeline size behind the cached queue; -1 until the first sync. */
    private var lastTimelineSize = -1

    /** Mirrored from session extras, so it survives a queue rebuild. */
    private var radioProfileId: String? = null

    init {
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val mediaController = MediaController.Builder(context, token)
                // The service is the single source of truth for radio mode and
                // publishes it as session extras; without a listener the UI would
                // never see it change.
                .setListener(object : MediaController.Listener {
                    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                        radioProfileId = extras.getString(RadioCommands.EXTRA_PROFILE_ID)
                        controller.let { syncState(it) }
                    }
                })
                .buildAsync().await()
            controller = mediaController
            radioProfileId =
                mediaController.sessionExtras.getString(RadioCommands.EXTRA_PROFILE_ID)
            mediaController.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) =
                    syncState(player, events)
            })
            syncState(mediaController)
        }
    }

    private fun syncState(player: Player, events: Player.Events? = null) {
        val metadata: MediaMetadata? = player.currentMediaItem?.mediaMetadata
        // Rebuild the queue list when the timeline changes, and also whenever its
        // size moved since the last rebuild — swapping players (casting)
        // republishes the timeline in stages and can drop the change event,
        // which once left "Up next" stuck on a transient two-item queue.
        // Compare against the previous timeline size rather than the cached
        // list's size: the list is filtered to episodes, so a single non-episode
        // item would make the two differ forever and rebuild on every event.
        val timelineSize = player.mediaItemCount
        val queue = if (
            events == null ||
            events.contains(Player.EVENT_TIMELINE_CHANGED) ||
            timelineSize != lastTimelineSize
        ) {
            lastTimelineSize = timelineSize
            (0 until timelineSize).mapNotNull { index ->
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
            radioProfileId = radioProfileId,
            errorMessage = player.playerError?.let { error ->
                // media3 wraps the cause, so a dead network reaches the user as
                // "No internet connection" rather than a source-error dump.
                "Playback failed: ${friendlyError(error, fallback = error.errorCodeName)}"
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

    /**
     * Starts radio for [profileId]; the service owns the queue from then on.
     * Pass [episodeId] to begin on a specific pick chosen from a list.
     */
    fun startRadio(profileId: String, episodeId: String? = null) {
        val args = Bundle().apply {
            putString(RadioCommands.EXTRA_PROFILE_ID, profileId)
            episodeId?.let { putString(RadioCommands.EXTRA_EPISODE_ID, it) }
        }
        controller?.sendCustomCommand(RadioCommands.START, args)
    }

    /** "Not now" — cooldowns the current pick and loads the next one. */
    fun skipRadio() {
        controller?.sendCustomCommand(RadioCommands.SKIP, Bundle.EMPTY)
    }

    /** Leaves radio; whatever is queued keeps playing as an ordinary queue. */
    fun stopRadio() {
        controller?.sendCustomCommand(RadioCommands.STOP, Bundle.EMPTY)
    }

    /** Appends to the queue without disturbing what is playing. */
    fun enqueue(episodeIds: List<String>) {
        val mediaController = controller ?: return
        mediaController.addMediaItems(
            episodeIds.map { MediaItem.Builder().setMediaId(MediaIds.episode(it)).build() },
        )
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
