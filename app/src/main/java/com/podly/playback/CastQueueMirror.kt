package com.podly.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ForwardingTimeline
import com.google.android.gms.cast.framework.media.MediaQueue
import java.util.IdentityHashMap

/**
 * The receiver's queue, item by item: what [CastQueueMirror] asks for the items
 * Media3's Cast timeline leaves out. Behind an interface so tests need no Cast session.
 */
internal interface ReceiverQueue {
    /**
     * The receiver id and item at [index] of the receiver's queue, if the receiver
     * has sent it; with [fetch], asks the receiver for it when it hasn't.
     */
    fun itemAt(index: Int, fetch: Boolean): Pair<Int, MediaItem>?

    /**
     * Calls [onChanged] whenever requested items arrive, with true when the receiver
     * reloaded its whole queue (ids already read may now name other items); null stops.
     */
    fun setOnChanged(onChanged: ((reloaded: Boolean) -> Unit)?)
}

/** [ReceiverQueue] over a Cast session's MediaQueue, converted the way Media3 converts the items it does describe. */
@OptIn(UnstableApi::class)
internal class MediaQueueSource(
    private val queue: MediaQueue,
    private val converter: MediaItemConverter,
) : ReceiverQueue {
    private var callback: MediaQueue.Callback? = null

    init {
        // The whole queue fits, so nothing fetched is evicted before it is read.
        queue.setCacheCapacity(MAX_CAST_QUEUE_ITEMS * 2)
    }

    override fun itemAt(index: Int, fetch: Boolean): Pair<Int, MediaItem>? {
        val item = runCatching { queue.getItemAtIndex(index, fetch) }.getOrNull() ?: return null
        // An item another sender queued may carry no MediaInfo; it stays unfilled.
        val mediaItem = runCatching { converter.toMediaItem(item) }.getOrNull() ?: return null
        return item.itemId to mediaItem
    }

    override fun setOnChanged(onChanged: ((reloaded: Boolean) -> Unit)?) {
        callback?.let(queue::unregisterCallback)
        callback = onChanged?.let { notify ->
            object : MediaQueue.Callback() {
                override fun itemsUpdatedAtIndexes(indexes: IntArray) = notify(false)

                override fun itemsReloaded() = notify(true)
            }.also(queue::registerCallback)
        }
    }
}

/**
 * Passes [delegate] every event, with the items the receiver hasn't described
 * filled in: the timeline [fill] makes of a new one, and, from the filled
 * [current] timeline, the item a transition moves to and the items in a
 * discontinuity's positions. Jumping to such an item otherwise reported
 * MediaItem.EMPTY, so the service couldn't restore that episode's saved position.
 *
 * Every method is forwarded by hand: `Player.Listener by delegate` compiles, but
 * Kotlin does not delegate Java default methods, so every event but the timeline
 * would silently stop. CastQueueMirrorTest fails if Media3 adds one this misses.
 */
@OptIn(UnstableApi::class)
internal class TimelineFillingListener(
    private val delegate: Player.Listener,
    private val fill: (Timeline) -> Timeline,
    private val current: () -> Timeline,
    private val currentIndex: () -> Int,
) : Player.Listener {
    override fun onTimelineChanged(timeline: Timeline, reason: Int) =
        delegate.onTimelineChanged(fill(timeline), reason)

    override fun onEvents(player: Player, events: Player.Events) = delegate.onEvents(player, events)

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val filled = mediaItem?.takeIf { it.mediaId.isEmpty() }?.let {
            val timeline = current()
            val index = currentIndex()
            if (index in 0 until timeline.windowCount) timeline.getWindow(index, Timeline.Window()).mediaItem else null
        }
        delegate.onMediaItemTransition(filled ?: mediaItem, reason)
    }

    /** [position] with its item filled in, when the window it names is still that index. */
    private fun filled(position: Player.PositionInfo): Player.PositionInfo {
        if (position.mediaItem?.mediaId?.isEmpty() != true) return position
        val timeline = current()
        if (position.mediaItemIndex !in 0 until timeline.windowCount) return position
        val window = timeline.getWindow(position.mediaItemIndex, Timeline.Window())
        if (window.uid != position.windowUid || window.mediaItem.mediaId.isEmpty()) return position
        return Player.PositionInfo(
            position.windowUid,
            position.mediaItemIndex,
            window.mediaItem,
            position.periodUid,
            position.periodIndex,
            position.positionMs,
            position.contentPositionMs,
            position.adGroupIndex,
            position.adIndexInAdGroup,
        )
    }

    override fun onTracksChanged(tracks: Tracks) = delegate.onTracksChanged(tracks)

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
        delegate.onMediaMetadataChanged(mediaMetadata)

    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) =
        delegate.onPlaylistMetadataChanged(mediaMetadata)

    override fun onIsLoadingChanged(isLoading: Boolean) = delegate.onIsLoadingChanged(isLoading)

    @Deprecated("Forwarded for listeners that still use it")
    @Suppress("DEPRECATION")
    override fun onLoadingChanged(isLoading: Boolean) = delegate.onLoadingChanged(isLoading)

    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
        delegate.onAvailableCommandsChanged(availableCommands)

    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
        delegate.onTrackSelectionParametersChanged(parameters)

    @Deprecated("Forwarded for listeners that still use it")
    @Suppress("DEPRECATION")
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
        delegate.onPlayerStateChanged(playWhenReady, playbackState)

    override fun onPlaybackStateChanged(playbackState: Int) =
        delegate.onPlaybackStateChanged(playbackState)

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
        delegate.onPlayWhenReadyChanged(playWhenReady, reason)

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
        delegate.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)

    override fun onIsPlayingChanged(isPlaying: Boolean) = delegate.onIsPlayingChanged(isPlaying)

    override fun onRepeatModeChanged(repeatMode: Int) = delegate.onRepeatModeChanged(repeatMode)

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
        delegate.onShuffleModeEnabledChanged(shuffleModeEnabled)

    override fun onPlayerError(error: PlaybackException) = delegate.onPlayerError(error)

    override fun onPlayerErrorChanged(error: PlaybackException?) = delegate.onPlayerErrorChanged(error)

    @Deprecated("Forwarded for listeners that still use it")
    @Suppress("DEPRECATION")
    override fun onPositionDiscontinuity(reason: Int) = delegate.onPositionDiscontinuity(reason)

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) = delegate.onPositionDiscontinuity(filled(oldPosition), filled(newPosition), reason)

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
        delegate.onPlaybackParametersChanged(playbackParameters)

    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) =
        delegate.onSeekBackIncrementChanged(seekBackIncrementMs)

    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
        delegate.onSeekForwardIncrementChanged(seekForwardIncrementMs)

    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
        delegate.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)

    override fun onAudioSessionIdChanged(audioSessionId: Int) =
        delegate.onAudioSessionIdChanged(audioSessionId)

    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) =
        delegate.onAudioAttributesChanged(audioAttributes)

    override fun onVolumeChanged(volume: Float) = delegate.onVolumeChanged(volume)

    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
        delegate.onSkipSilenceEnabledChanged(skipSilenceEnabled)

    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = delegate.onDeviceInfoChanged(deviceInfo)

    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) =
        delegate.onDeviceVolumeChanged(volume, muted)

    override fun onVideoSizeChanged(videoSize: VideoSize) = delegate.onVideoSizeChanged(videoSize)

    override fun onSurfaceSizeChanged(width: Int, height: Int) = delegate.onSurfaceSizeChanged(width, height)

    override fun onRenderedFirstFrame() = delegate.onRenderedFirstFrame()

    @Deprecated("Forwarded for listeners that still use it")
    @Suppress("DEPRECATION")
    override fun onCues(cues: List<Cue>) = delegate.onCues(cues)

    override fun onCues(cueGroup: CueGroup) = delegate.onCues(cueGroup)

    override fun onMetadata(metadata: Metadata) = delegate.onMetadata(metadata)

}

/**
 * Wraps the Cast player so the session sees the whole queue.
 *
 * Media3's Cast timeline only has MediaItems for the receiver's prefetch window,
 * about the current item and its neighbours; every other item comes back as
 * MediaItem.EMPTY, with no mediaId. Anything that read the queue by mediaId lost
 * those items: handing playback back to the phone kept three episodes of a
 * twenty-episode queue, the saved queue shrank the same way, and Up next showed a
 * few rows whose positions no longer matched the player's indexes.
 *
 * The missing items are asked of the receiver itself ([attach]), kept by receiver
 * item id for that session only, and filled into the timeline for the getters and
 * for listener events: MediaSession builds its controllers' timeline from
 * onTimelineChanged's argument rather than from [getCurrentTimeline]. When items
 * arrive, listeners are told the timeline changed. Nothing is inferred from what
 * was sent, which went wrong whenever edits overlapped or the receiver reused ids.
 *
 * It is also the last stop before the receiver, so every queue it is handed is
 * made sendable here: at most [MAX_CAST_QUEUE_ITEMS] items and streams instead of
 * downloads. Callers trim too, but a result resolved for the local player can
 * arrive after Cast has taken over: a restored library queue that reached the
 * Cast player whole overflowed Cast's 512 KB message and crashed the app.
 */
@OptIn(UnstableApi::class)
internal class CastQueueMirror(
    player: Player,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ForwardingPlayer(player) {
    /** The connected session's queue, or null between sessions. */
    private var receiver: ReceiverQueue? = null

    /** Receiver item id to item, for the attached session. */
    private val fetched = HashMap<Any, MediaItem>()

    /** How often each receiver id has been asked for, so one that never arrives stops being asked. */
    private val attempts = HashMap<Any, Int>()

    /** Ids asked for and not answered yet; never more than MediaQueue holds. */
    private val pending = HashSet<Any>()

    /** Progress ticks [pending] has waited without an answer. */
    private var unansweredTicks = 0

    /** The queue last sent, standing in for items the receiver still owes at a handover. */
    private var lastSent: List<MediaItem> = emptyList()

    /** When a queue was last sent to an empty timeline that hasn't shown it yet; null otherwise. */
    private var loadSentAtMs: Long? = null

    /**
     * Play or pause asked for while that load was in flight. RemoteCastPlayer sends
     * neither without a media status, and the receiver's first status then
     * overwrites the request, so it is made again once the receiver reports.
     */
    private var pendingPlayWhenReady: Boolean? = null

    /**
     * The wrapper registered for each listener. ForwardingPlayer finds listeners
     * to remove by identity, so removal must hand back this same instance.
     */
    private val wrappers = IdentityHashMap<Player.Listener, TimelineFillingListener>()

    init {
        // Ahead of every listener added later, so new items are requested, and
        // those already fetched are on hand, before anyone reads the timeline.
        super.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (!timeline.isEmpty) {
                    val sentAt = loadSentAtMs
                    val wanted = pendingPlayWhenReady
                    loadSentAtMs = null
                    pendingPlayWhenReady = null
                    // The receiver has reported, so a play or pause now reaches it;
                    // not once the load was given up on, when this queue may be
                    // another sender's.
                    if (wanted != null && sentAt != null && clock() - sentAt < LOAD_GRACE_MS &&
                        super@CastQueueMirror.getPlayWhenReady() != wanted
                    ) {
                        super@CastQueueMirror.setPlayWhenReady(wanted)
                    }
                }
                collect(timeline, fetch = true)
            }
        })
    }

    override fun play() {
        if (hasLoadInFlight()) pendingPlayWhenReady = true
        super.play()
    }

    override fun pause() {
        if (hasLoadInFlight()) pendingPlayWhenReady = false
        super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (hasLoadInFlight()) pendingPlayWhenReady = playWhenReady
        super.setPlayWhenReady(playWhenReady)
    }

    /**
     * Whether a queue has been sent that the timeline doesn't show yet. The Cast
     * player shows a load only once the receiver reports it, so until then the
     * player reads as empty, and a play() right behind the load looks to Media3
     * like play on an empty player, which restores the saved queue over it.
     * Given up after [LOAD_GRACE_MS], so a load the receiver never takes can't
     * block that restore for good.
     */
    fun hasLoadInFlight(): Boolean {
        val sentAt = loadSentAtMs ?: return false
        if (!rawTimeline().isEmpty) {
            loadSentAtMs = null
            return false
        }
        if (clock() - sentAt < LOAD_GRACE_MS) return true
        // Given up on: a play or pause asked for then is not replayed much later.
        loadSentAtMs = null
        pendingPlayWhenReady = null
        return false
    }

    /** Starts filling from [queue], a newly connected session's; null clears. */
    fun attach(queue: ReceiverQueue?) {
        detach()
        receiver = queue ?: return
        queue.setOnChanged { reloaded ->
            val forgot = reloaded && forget()
            // An answer covers the batch asked for, and makes room to ask for the next.
            pending.clear()
            unansweredTicks = 0
            if (collect(rawTimeline(), fetch = true) || forgot) notifyTimelineFilled()
        }
        collect(rawTimeline(), fetch = true)
    }

    /**
     * Whether every item of the timeline has its mediaId: false while the receiver
     * still owes some, which a handover must not mistake for items removed.
     */
    fun isComplete(): Boolean {
        val timeline = currentTimeline
        val window = Timeline.Window()
        return (0 until timeline.windowCount).none { timeline.getWindow(it, window).mediaItem.mediaId.isEmpty() }
    }

    /**
     * Asks again for what the receiver hasn't answered after two calls: MediaQueue
     * drops a failed fetch without a callback, so nothing else would. Called on
     * the service's progress tick.
     */
    fun retryUnanswered() {
        if (pending.isEmpty()) return
        if (++unansweredTicks < 2) return
        pending.clear()
        unansweredTicks = 0
        if (collect(rawTimeline(), fetch = true)) notifyTimelineFilled()
    }

    /**
     * The queue to hand back to the phone: the timeline's items, with the ones the
     * receiver still owes taken from the queue last sent. Only the sent episodes
     * not already in the timeline are used, in the order they were sent, and only
     * when they exactly fill the gaps: a move made since can then reorder them but
     * never duplicate or drop one. Disconnecting seconds after connecting
     * otherwise dropped every item not yet fetched.
     */
    fun itemsForHandover(): List<MediaItem> {
        val timeline = currentTimeline
        val window = Timeline.Window()
        val items = (0 until timeline.windowCount).map { timeline.getWindow(it, window).mediaItem }
        val gaps = items.count { it.mediaId.isEmpty() }
        if (gaps == 0) return items
        val unaccounted = lastSent.toMutableList()
        for (item in items) {
            if (item.mediaId.isEmpty()) continue
            val sent = unaccounted.indexOfFirst { it.mediaId == item.mediaId }
            if (sent >= 0) unaccounted.removeAt(sent)
        }
        if (unaccounted.size != gaps) return items
        val fill = unaccounted.iterator()
        return items.map { if (it.mediaId.isEmpty()) fill.next() else it }
    }

    /** Drops everything read so far; true if there was anything. */
    private fun forget(): Boolean {
        val had = fetched.isNotEmpty()
        fetched.clear()
        attempts.clear()
        pending.clear()
        unansweredTicks = 0
        return had
    }

    /** The Cast player's own timeline, before filling. */
    private fun rawTimeline(): Timeline = super.getCurrentTimeline()

    /** Forgets the session: item ids mean nothing in the next one. */
    fun detach() {
        receiver?.setOnChanged(null)
        receiver = null
        loadSentAtMs = null
        pendingPlayWhenReady = null
        forget()
    }

    /**
     * Keeps what the receiver has sent for [timeline]'s undescribed items, and with
     * [fetch] asks for up to [FETCH_PAGE] of the rest; true if anything was new.
     */
    private fun collect(timeline: Timeline, fetch: Boolean): Boolean {
        val queue = receiver ?: return false
        if (timeline.isEmpty) {
            // A cleared queue: the next one may reuse its ids.
            forget()
            return false
        }
        val window = Timeline.Window()
        val present = HashSet<Any>()
        var learned = false
        for (index in 0 until timeline.windowCount) {
            timeline.getWindow(index, window)
            val uid = window.uid
            present += uid
            if (window.mediaItem.mediaId.isNotEmpty() || uid in fetched) continue
            // MediaQueue keeps at most 20 pending requests and drops the oldest
            // beyond that, so no more than that are ever outstanding; the rest wait
            // for the next answer. An id already asked for is not asked, or
            // counted, again until it is answered or given up on.
            val ask = fetch && uid !in pending && pending.size < FETCH_PAGE &&
                attempts.getOrDefault(uid, 0) < MAX_ATTEMPTS
            val sent = queue.itemAt(index, ask)
            if (sent == null) {
                if (ask) {
                    pending += uid
                    attempts[uid] = attempts.getOrDefault(uid, 0) + 1
                }
                continue
            }
            pending -= uid
            // The receiver's queue and the timeline are read at different moments;
            // an item is only kept for the id it was sent under.
            if (sent.first != uid) continue
            fetched[uid] = sent.second
            learned = true
        }
        fetched.keys.retainAll(present)
        attempts.keys.retainAll(present)
        pending.retainAll(present)
        return learned
    }

    private fun filled(timeline: Timeline): Timeline {
        val window = Timeline.Window()
        val fillable = (0 until timeline.windowCount).any { index ->
            timeline.getWindow(index, window)
            window.mediaItem.mediaId.isEmpty() && window.uid in fetched
        }
        if (!fillable) return timeline
        val items = HashMap(fetched)
        return object : ForwardingTimeline(timeline) {
            override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long,
            ): Window {
                super.getWindow(windowIndex, window, defaultPositionProjectionUs)
                if (window.mediaItem.mediaId.isEmpty()) items[window.uid]?.let { window.mediaItem = it }
                return window
            }
        }
    }

    /** Tells every listener the timeline changed: only now does it name the items just fetched. */
    private fun notifyTimelineFilled() {
        val timeline = currentTimeline
        val listeners = synchronized(wrappers) { wrappers.values.toList() }
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_TIMELINE_CHANGED).build())
        listeners.forEach { it.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) }
        listeners.forEach { it.onEvents(this, events) }
    }

    override fun getCurrentTimeline(): Timeline = filled(rawTimeline())

    override fun getMediaItemAt(index: Int): MediaItem =
        currentTimeline.getWindow(index, Timeline.Window()).mediaItem

    override fun getCurrentMediaItem(): MediaItem? {
        val timeline = currentTimeline
        if (timeline.isEmpty) return null
        return timeline.getWindow(currentMediaItemIndex, Timeline.Window()).mediaItem
    }

    override fun addListener(listener: Player.Listener) {
        val wrapper = synchronized(wrappers) {
            wrappers.getOrPut(listener) {
                TimelineFillingListener(listener, ::filled, ::getCurrentTimeline, ::getCurrentMediaItemIndex)
            }
        }
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapper = synchronized(wrappers) { wrappers.remove(listener) } ?: return
        super.removeListener(wrapper)
    }

    // A new queue replaces the receiver's, and its ids may repeat the old ones:
    // what they named before is forgotten, and read again as they come. The queue
    // as sent is kept for itemsForHandover. Every setter goes through [sent] or
    // [castPlayable]: ForwardingPlayer forwards each overload separately, so one
    // left out would reach RemoteCastPlayer untrimmed.

    /** Records [items] as the queue now on its way to the receiver. */
    private fun sent(items: List<MediaItem>) {
        // RemoteCastPlayer ignores an empty list, so nothing is replaced.
        if (items.isEmpty()) return
        forget()
        lastSent = items.toList()
        loadSentAtMs = if (rawTimeline().isEmpty) clock() else null
        if (loadSentAtMs == null) pendingPlayWhenReady = null
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) = setMediaItems(mediaItems, true)

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        if (mediaItems.size <= MAX_CAST_QUEUE_ITEMS) {
            val items = mediaItems.map(::castPlayable)
            sent(items)
            super.setMediaItems(items, resetPosition)
            return
        }
        // Trimming needs the index to keep, worked out as RemoteCastPlayer would.
        if (resetPosition) {
            setMediaItems(mediaItems, 0, C.TIME_UNSET)
        } else {
            setMediaItems(mediaItems, currentMediaItemIndex, contentPosition)
        }
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        var index = startIndex
        var position = startPositionMs
        if (mediaItems.size > MAX_CAST_QUEUE_ITEMS && index == C.INDEX_UNSET) {
            // RemoteCastPlayer's reading of INDEX_UNSET: stay where playback is.
            index = currentMediaItemIndex
            position = currentPosition
        }
        val (window, windowIndex) = castQueueWindow(mediaItems, index)
        val items = window.map(::castPlayable)
        sent(items)
        super.setMediaItems(items, windowIndex, position)
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        val item = castPlayable(mediaItem)
        sent(listOf(item))
        super.setMediaItem(item)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        val item = castPlayable(mediaItem)
        sent(listOf(item))
        super.setMediaItem(item, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        val item = castPlayable(mediaItem)
        sent(listOf(item))
        super.setMediaItem(item, resetPosition)
    }

    // Additions go to the receiver as they are; only the URIs need changing. Items
    // added while casting are not trimmed (Play next, Add to queue and radio
    // refills add a few at a time).

    override fun addMediaItem(mediaItem: MediaItem) = super.addMediaItem(castPlayable(mediaItem))

    override fun addMediaItem(index: Int, mediaItem: MediaItem) =
        super.addMediaItem(index, castPlayable(mediaItem))

    override fun addMediaItems(mediaItems: List<MediaItem>) =
        super.addMediaItems(mediaItems.map(::castPlayable))

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) =
        super.addMediaItems(index, mediaItems.map(::castPlayable))

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) =
        super.replaceMediaItem(index, castPlayable(mediaItem))

    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) =
        super.replaceMediaItems(fromIndex, toIndex, mediaItems.map(::castPlayable))

    private companion object {
        /** MediaQueue's own limit on pending item requests. */
        const val FETCH_PAGE = 20
        const val MAX_ATTEMPTS = 3

        /** Long enough for a receiver on slow Wi-Fi to answer a LOAD. */
        const val LOAD_GRACE_MS = 15_000L
    }
}

/**
 * Drops the null entries of [items] and says where the entry at [index] lands
 * among the rest; a dropped entry hands over to the next one kept, or the last.
 * When a queue moves between players, an item that can't be carried across must
 * not shift which episode was current.
 */
internal fun <T : Any> compactAt(items: List<T?>, index: Int): Pair<List<T>, Int> {
    val kept = items.filterNotNull()
    val before = items.take(index.coerceAtLeast(0)).count { it != null }
    return kept to before.coerceAtMost((kept.size - 1).coerceAtLeast(0))
}
