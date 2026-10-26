package com.podly.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
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
import java.util.IdentityHashMap

/**
 * The queue sent to the Cast receiver, so the Cast timeline can say what every
 * item is.
 *
 * Media3's Cast timeline only has MediaItems for the receiver's prefetch window,
 * about the current item and its neighbours; every other item comes back as
 * MediaItem.EMPTY, with no mediaId. Anything that read the queue by mediaId lost
 * those items: handing playback back to the phone kept three episodes of a
 * twenty-episode queue, the saved queue shrank the same way, and Up next showed
 * a few rows whose positions no longer matched the player's indexes.
 *
 * What is filled in is keyed on the receiver's item ids (the timeline's window
 * uids), each matched once to an item that was sent. Changes are recorded the way
 * RemoteCastPlayer sends them, against the queue the receiver last confirmed and
 * by item id ("insert before this item", "remove these"), so the items sent but
 * not yet matched keep the order the receiver will give them. When ids the
 * receiver hasn't been matched to appear, they are paired with those items in
 * order, and only if the counts agree; an id that appeared with nothing waiting
 * came from another sender and is never paired.
 */
@OptIn(UnstableApi::class)
internal class SentQueue {
    /** One item sent to the receiver; [uid] is its receiver id once matched. */
    private class Entry(val item: MediaItem, var uid: Any? = null)

    /** What was sent, in the order the receiver will hold it once it has applied every change. */
    private val entries = ArrayList<Entry>()

    /** Receiver item id to its MediaItem, matched from [entries] or described by the receiver. */
    private val byUid = HashMap<Any, MediaItem>()

    /**
     * Ids never to pair with a sent item: those of a queue a [set] replaces (the
     * receiver gives the new one fresh ids, but shows the old one for a moment),
     * and those that appeared while nothing sent was waiting (another sender's).
     */
    private val unpairable = HashSet<Any>()

    fun set(confirmed: Timeline, items: List<MediaItem>) {
        // RemoteCastPlayer ignores an empty list rather than clearing the receiver.
        if (items.isEmpty()) return
        (0 until confirmed.windowCount).mapTo(unpairable) { uidAt(confirmed, it) }
        entries.clear()
        items.mapTo(entries) { Entry(it) }
    }

    /** Before the item at [index] of [confirmed], or appended past its end. */
    fun add(confirmed: Timeline, index: Int, items: List<MediaItem>) {
        val anchor = if (index < confirmed.windowCount) uidAt(confirmed, index) else null
        insertBefore(anchor, items.map { Entry(it) })
    }

    fun move(confirmed: Timeline, fromIndex: Int, toIndex: Int, newIndex: Int) {
        val size = confirmed.windowCount
        val to = minOf(toIndex, size)
        val target = minOf(newIndex, size - (to - fromIndex))
        if (fromIndex >= size || fromIndex == to || fromIndex == target) return
        val moved = take((fromIndex until to).map { uidAt(confirmed, it) })
        // Where RemoteCastPlayer re-inserts them: before this item, or at the end.
        val insertBefore = if (fromIndex < target) target + (to - fromIndex) else target
        insertBefore(if (insertBefore < size) uidAt(confirmed, insertBefore) else null, moved)
    }

    fun remove(confirmed: Timeline, fromIndex: Int, toIndex: Int) {
        val to = minOf(toIndex, confirmed.windowCount)
        if (fromIndex >= confirmed.windowCount || fromIndex == to) return
        take((fromIndex until to).map { uidAt(confirmed, it) })
    }

    fun replace(confirmed: Timeline, fromIndex: Int, toIndex: Int, items: List<MediaItem>) {
        if (fromIndex > confirmed.windowCount) return
        val to = minOf(toIndex, confirmed.windowCount)
        add(confirmed, to, items)
        remove(confirmed, fromIndex, to)
    }

    private fun uidAt(timeline: Timeline, index: Int): Any = timeline.getWindow(index, Timeline.Window()).uid

    /** Takes out the entries matched to [uids]; ids never matched have no entry to take. */
    private fun take(uids: List<Any>): List<Entry> {
        val wanted = uids.toHashSet()
        val taken = entries.filter { it.uid in wanted }
        entries.removeAll(taken.toSet())
        return taken
    }

    private fun insertBefore(anchor: Any?, new: List<Entry>) {
        val at = anchor?.let { uid -> entries.indexOfFirst { it.uid == uid } } ?: -1
        // An anchor with no entry (another sender's item) can't be placed; the end
        // keeps the order among what was sent, which is all the pairing relies on.
        if (at < 0) entries.addAll(new) else entries.addAll(at, new)
    }

    /**
     * [timeline] with every item the receiver hasn't described filled in from
     * what was sent for its id. An item stays as the receiver gave it when its id
     * was never matched: another sender's, or one whose pairing was ambiguous.
     */
    fun fill(timeline: Timeline): Timeline {
        learn(timeline)
        val window = Timeline.Window()
        val fillable = (0 until timeline.windowCount).any { index ->
            timeline.getWindow(index, window)
            window.mediaItem.mediaId.isEmpty() && window.uid in byUid
        }
        if (!fillable) return timeline
        val sent = HashMap(byUid)
        return object : ForwardingTimeline(timeline) {
            override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long,
            ): Window {
                super.getWindow(windowIndex, window, defaultPositionProjectionUs)
                if (window.mediaItem.mediaId.isEmpty()) sent[window.uid]?.let { window.mediaItem = it }
                return window
            }
        }
    }

    private fun learn(timeline: Timeline) {
        if (timeline.isEmpty) return
        val window = Timeline.Window()
        val uids = (0 until timeline.windowCount).map { index ->
            timeline.getWindow(index, window)
            // The receiver's own description wins over anything matched earlier.
            if (window.mediaItem.mediaId.isNotEmpty()) byUid[window.uid] = window.mediaItem
            window.uid
        }
        val present = uids.toHashSet()
        byUid.keys.retainAll(present)
        unpairable.retainAll(present)
        // Gone from the receiver, so gone from the queue: never freed to pair again,
        // or another sender's replacement would take its identity.
        entries.removeAll { it.uid != null && it.uid !in present }

        val matched = entries.mapNotNullTo(HashSet()) { it.uid }
        val fresh = uids.filter { it !in matched && it !in unpairable }
        val waiting = entries.filter { it.uid == null }
        if (waiting.isEmpty()) {
            unpairable.addAll(fresh)
            return
        }
        // More new ids than items waiting means some are another sender's, and fewer
        // means the receiver hasn't applied every change yet: pair nothing either way.
        if (fresh.size != waiting.size) return
        val agree = fresh.indices.all { index ->
            byUid[fresh[index]]?.let { it.mediaId == waiting[index].item.mediaId } != false
        }
        if (!agree) return
        fresh.forEachIndexed { index, uid ->
            waiting[index].uid = uid
            byUid.putIfAbsent(uid, waiting[index].item)
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
 * Wraps the Cast player so the session sees the whole queue: records what is
 * sent ([SentQueue]) and fills in the timeline from it. Listeners get the filled
 * timeline too, because MediaSession builds its controllers' timeline from
 * onTimelineChanged's argument rather than from [getCurrentTimeline].
 */
@OptIn(UnstableApi::class)
internal class CastQueueMirror(player: Player) : ForwardingPlayer(player) {
    private val sent = SentQueue()

    /**
     * The wrapper registered for each listener. ForwardingPlayer finds listeners
     * to remove by identity, so removal must hand back this same instance.
     */
    private val wrappers = IdentityHashMap<Player.Listener, TimelineFillingListener>()

    override fun getCurrentTimeline(): Timeline = sent.fill(super.getCurrentTimeline())

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
                TimelineFillingListener(listener, sent::fill, ::getCurrentTimeline, ::getCurrentMediaItemIndex)
            }
        }
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapper = synchronized(wrappers) { wrappers.remove(listener) } ?: return
        super.removeListener(wrapper)
    }

    // Every overload is overridden: ForwardingPlayer sends each one straight to the
    // same overload of the Cast player, so one left out would bypass the record.
    // Changes are recorded against the timeline the Cast player held when called,
    // which is the one it resolves indexes against.

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        val confirmed = super.getCurrentTimeline()
        super.setMediaItems(mediaItems)
        sent.set(confirmed, mediaItems)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        val confirmed = super.getCurrentTimeline()
        super.setMediaItems(mediaItems, resetPosition)
        sent.set(confirmed, mediaItems)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        val confirmed = super.getCurrentTimeline()
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
        sent.set(confirmed, mediaItems)
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        val confirmed = super.getCurrentTimeline()
        super.setMediaItem(mediaItem)
        sent.set(confirmed, listOf(mediaItem))
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        val confirmed = super.getCurrentTimeline()
        super.setMediaItem(mediaItem, startPositionMs)
        sent.set(confirmed, listOf(mediaItem))
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        val confirmed = super.getCurrentTimeline()
        super.setMediaItem(mediaItem, resetPosition)
        sent.set(confirmed, listOf(mediaItem))
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        val confirmed = super.getCurrentTimeline()
        super.addMediaItem(mediaItem)
        sent.add(confirmed, Int.MAX_VALUE, listOf(mediaItem))
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        val confirmed = super.getCurrentTimeline()
        super.addMediaItem(index, mediaItem)
        sent.add(confirmed, index, listOf(mediaItem))
    }

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        val confirmed = super.getCurrentTimeline()
        super.addMediaItems(mediaItems)
        sent.add(confirmed, Int.MAX_VALUE, mediaItems)
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        val confirmed = super.getCurrentTimeline()
        super.addMediaItems(index, mediaItems)
        sent.add(confirmed, index, mediaItems)
    }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        val confirmed = super.getCurrentTimeline()
        super.moveMediaItem(currentIndex, newIndex)
        sent.move(confirmed, currentIndex, currentIndex + 1, newIndex)
    }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        val confirmed = super.getCurrentTimeline()
        super.moveMediaItems(fromIndex, toIndex, newIndex)
        sent.move(confirmed, fromIndex, toIndex, newIndex)
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        val confirmed = super.getCurrentTimeline()
        super.replaceMediaItem(index, mediaItem)
        sent.replace(confirmed, index, index + 1, listOf(mediaItem))
    }

    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) {
        val confirmed = super.getCurrentTimeline()
        super.replaceMediaItems(fromIndex, toIndex, mediaItems)
        sent.replace(confirmed, fromIndex, toIndex, mediaItems)
    }

    override fun removeMediaItem(index: Int) {
        val confirmed = super.getCurrentTimeline()
        super.removeMediaItem(index)
        sent.remove(confirmed, index, index + 1)
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        val confirmed = super.getCurrentTimeline()
        super.removeMediaItems(fromIndex, toIndex)
        sent.remove(confirmed, fromIndex, toIndex)
    }

    override fun clearMediaItems() {
        val confirmed = super.getCurrentTimeline()
        super.clearMediaItems()
        sent.remove(confirmed, 0, Int.MAX_VALUE)
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
