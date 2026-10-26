package com.podly.playback

import android.app.Application
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * Guards the cast queue filling: Media3's Cast timeline leaves every item outside
 * the receiver's prefetch window as MediaItem.EMPTY, and a cast round trip kept
 * three episodes of a twenty-episode queue.
 */
// Plain Application: PodlyApp's onCreate spins up WorkManager, which these tests don't need.
@Config(application = Application::class)
@RunWith(AndroidJUnit4::class)
class CastQueueMirrorTest {

    private fun item(id: String) = MediaItem.Builder().setMediaId(MediaIds.episode(id)).build()

    private fun items(vararg ids: String) = ids.map(::item)

    private fun episodes(vararg ids: String) = ids.map(MediaIds::episode)

    /** One receiver queue item: its id, and its MediaItem if Cast described it. */
    private data class Slot(val uid: Int, val item: MediaItem?)

    /** A receiver-shaped timeline, as Media3's CastTimeline presents it. */
    private class ReceiverTimeline(private val slots: List<Slot>) : Timeline() {
        override fun getWindowCount() = slots.size

        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long) =
            window.set(
                slots[windowIndex].uid,
                slots[windowIndex].item ?: MediaItem.EMPTY,
                /* manifest= */ null,
                C.TIME_UNSET,
                C.TIME_UNSET,
                C.TIME_UNSET,
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                /* liveConfiguration= */ null,
                /* defaultPositionUs= */ 0,
                C.TIME_UNSET,
                windowIndex,
                windowIndex,
                /* positionInFirstPeriodUs= */ 0,
            )

        override fun getPeriodCount() = slots.size

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean) =
            period.set(
                slots[periodIndex].uid,
                slots[periodIndex].uid,
                periodIndex,
                C.TIME_UNSET,
                /* positionInWindowUs= */ 0,
                AdPlaybackState.NONE,
                /* isPlaceholder= */ false,
            )

        override fun getIndexOfPeriod(uid: Any) =
            slots.indexOfFirst { it.uid == uid }.takeIf { it >= 0 } ?: C.INDEX_UNSET

        override fun getUidOfPeriod(periodIndex: Int): Any = slots[periodIndex].uid
    }

    /**
     * The receiver holding [sent] under ids 100, 101, ..., describing only the
     * positions in [known].
     */
    private fun receiver(sent: List<MediaItem>, known: Set<Int>, uids: List<Int> = sent.indices.map { 100 + it }) =
        ReceiverTimeline(sent.indices.map { Slot(uids[it], sent[it].takeIf { _ -> it in known }) })

    private fun Timeline.mediaIds() =
        (0 until windowCount).map { getWindow(it, Timeline.Window()).mediaItem.mediaId }

    // ---- filling from the receiver ----------------------------------------------

    /**
     * Stands in for the session's MediaQueue: items arrive when [deliver] is called,
     * and like MediaQueue it keeps only the latest 20 pending requests.
     */
    private class FakeReceiverQueue(var items: Map<Int, Pair<Int, MediaItem>>) : ReceiverQueue {
        val requested = mutableSetOf<Int>()
        private val pending = ArrayDeque<Int>()
        private val delivered = mutableSetOf<Int>()
        private var onChanged: ((Boolean) -> Unit)? = null

        override fun itemAt(index: Int, fetch: Boolean): Pair<Int, MediaItem>? {
            if (index in delivered) return items[index]
            if (fetch && index !in pending) {
                requested += index
                if (pending.size >= 20) pending.removeFirst()
                pending.addLast(index)
            }
            return null
        }

        override fun setOnChanged(onChanged: ((Boolean) -> Unit)?) {
            this.onChanged = onChanged
        }

        /** Answers every pending request, as one reply. */
        fun deliver() {
            delivered += pending
            pending.clear()
            onChanged?.invoke(false)
        }

        val listening get() = onChanged != null

        /** The fetch for everything pending failed: MediaQueue drops it without a callback. */
        fun failPending() {
            pending.clear()
        }

        /** The receiver reloaded its whole queue: everything is fetched again. */
        fun reload(newItems: Map<Int, Pair<Int, MediaItem>>) {
            items = newItems
            delivered.clear()
            onChanged?.invoke(true)
        }
    }

    /** A Cast player reduced to a timeline and its listeners. */
    private class FakeCastPlayer(var timeline: Timeline) {
        val listeners = mutableListOf<Player.Listener>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentTimeline" -> timeline
                "addListener" -> listeners += args[0] as Player.Listener
                "removeListener" -> listeners -= args[0] as Player.Listener
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    else -> null
                }
            }
        } as Player

        fun changeTimeline(new: Timeline) {
            timeline = new
            listeners.toList().forEach { it.onTimelineChanged(new, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
        }
    }

    /** What the receiver would send for each position of [items]: its id (100 + index) and item. */
    private fun receiverItems(items: List<MediaItem>) = items.indices.associateWith { (100 + it) to items[it] }

    @Test
    fun hiddenItemsAreFilledOnceTheReceiverSendsThem() {
        val queue = items("a", "b", "c", "d", "e")
        val cast = FakeCastPlayer(receiver(queue, known = setOf(0)))
        val mirror = CastQueueMirror(cast.player)
        val recorder = Recorder()
        mirror.addListener(recorder)
        val receiverQueue = FakeReceiverQueue(receiverItems(queue))

        mirror.attach(receiverQueue)
        assertEquals(setOf(1, 2, 3, 4), receiverQueue.requested) // only the hidden ones
        assertEquals(listOf(MediaIds.episode("a"), "", "", "", ""), mirror.currentTimeline.mediaIds())

        receiverQueue.deliver()

        assertEquals(episodes("a", "b", "c", "d", "e"), mirror.currentTimeline.mediaIds())
        assertEquals(MediaIds.episode("c"), mirror.getMediaItemAt(2).mediaId)
        // Listeners hear about it: the session's controllers take the timeline from this event.
        assertEquals(episodes("a", "b", "c", "d", "e"), recorder.timeline!!.mediaIds())
    }

    @Test
    fun aLongQueueIsFetchedInBatchesTheReceiverKeeps() {
        // 48 items, only the first described: more than MediaQueue's 20 pending requests.
        val queue = (1..48).map { item("ep$it") }
        val cast = FakeCastPlayer(receiver(queue, known = setOf(0)))
        val mirror = CastQueueMirror(cast.player)
        val receiverQueue = FakeReceiverQueue(receiverItems(queue))

        mirror.attach(receiverQueue)
        repeat(3) { receiverQueue.deliver() }

        assertEquals(queue.map { it.mediaId }, mirror.currentTimeline.mediaIds())
    }

    @Test
    fun timelineChangesBeforeTheFirstAnswerDontUseUpTries() {
        // MediaQueue waits before fetching, and the timeline can change meanwhile.
        val queue = (1..48).map { item("ep$it") }
        val cast = FakeCastPlayer(receiver(queue, known = setOf(0)))
        val mirror = CastQueueMirror(cast.player)
        val receiverQueue = FakeReceiverQueue(receiverItems(queue))
        mirror.attach(receiverQueue)
        repeat(4) { cast.changeTimeline(receiver(queue, known = setOf(0))) }

        repeat(3) { receiverQueue.deliver() }

        assertEquals(queue.map { it.mediaId }, mirror.currentTimeline.mediaIds())
    }

    @Test
    fun aFailedFetchIsAskedForAgainOnTheTick() {
        val queue = items("a", "b", "c")
        val cast = FakeCastPlayer(receiver(queue, known = setOf(0)))
        val mirror = CastQueueMirror(cast.player)
        val receiverQueue = FakeReceiverQueue(receiverItems(queue))
        mirror.attach(receiverQueue)
        receiverQueue.failPending()

        mirror.retryUnanswered() // one tick: may still be on its way
        receiverQueue.deliver()
        assertEquals(false, mirror.isComplete())

        mirror.retryUnanswered() // two: asked again
        receiverQueue.deliver()
        assertEquals(episodes("a", "b", "c"), mirror.currentTimeline.mediaIds())
    }

    @Test
    fun anEmptySetLeavesTheFilledQueueAlone() {
        // RemoteCastPlayer ignores it, so the receiver's queue is unchanged.
        val queue = items("a", "b", "c")
        val cast = FakeCastPlayer(receiver(queue, known = setOf(0)))
        val mirror = CastQueueMirror(cast.player)
        val receiverQueue = FakeReceiverQueue(receiverItems(queue))
        mirror.attach(receiverQueue)
        receiverQueue.deliver()

        mirror.setMediaItems(emptyList(), 0, 0)

        assertEquals(true, mirror.isComplete())
        assertEquals(episodes("a", "b", "c"), mirror.currentTimeline.mediaIds())
    }

    @Test
    fun anItemSentUnderAnotherIdIsNotUsed() {
        val queue = items("a", "b", "c")
        val cast = FakeCastPlayer(receiver(queue, known = setOf(0)))
        val mirror = CastQueueMirror(cast.player)
        // The receiver's queue moved on between the timeline and the fetch.
        val receiverQueue = FakeReceiverQueue(mapOf(1 to (101 to queue[1]), 2 to (999 to item("z"))))

        mirror.attach(receiverQueue)
        receiverQueue.deliver()

        assertEquals(listOf(MediaIds.episode("a"), MediaIds.episode("b"), ""), mirror.currentTimeline.mediaIds())
    }

    @Test
    fun aNewSessionNeverSeesTheLastOnesItems() {
        // Receiver ids restart in a new session; what id 101 was last time means nothing now.
        val first = items("a", "b", "c")
        val cast = FakeCastPlayer(receiver(first, known = setOf(0)))
        val mirror = CastQueueMirror(cast.player)
        val firstQueue = FakeReceiverQueue(receiverItems(first))
        mirror.attach(firstQueue)
        firstQueue.deliver()
        mirror.detach()
        assertEquals(false, firstQueue.listening)

        val second = items("x", "y", "z")
        cast.timeline = receiver(second, known = setOf(0))
        assertEquals(listOf(MediaIds.episode("x"), "", ""), mirror.currentTimeline.mediaIds())
        val secondQueue = FakeReceiverQueue(receiverItems(second))
        mirror.attach(secondQueue)
        secondQueue.deliver()
        assertEquals(episodes("x", "y", "z"), mirror.currentTimeline.mediaIds())
    }

    @Test
    fun aTimelineChangeAsksForTheNewItems() {
        val queue = items("a", "b")
        val cast = FakeCastPlayer(receiver(queue, known = setOf(0, 1)))
        val mirror = CastQueueMirror(cast.player)
        val longer = items("a", "b", "x")
        val receiverQueue = FakeReceiverQueue(receiverItems(longer))
        mirror.attach(receiverQueue)
        assertEquals(emptySet<Int>(), receiverQueue.requested)

        // Something was appended; the receiver doesn't describe it yet.
        cast.changeTimeline(receiver(longer, known = setOf(0, 1)))
        assertEquals(setOf(2), receiverQueue.requested)
        receiverQueue.deliver()

        assertEquals(episodes("a", "b", "x"), mirror.currentTimeline.mediaIds())
    }

    @Test
    fun aHandoverTakesWhatTheReceiverStillOwesFromTheQueueSent() {
        // Disconnecting seconds after connecting: nothing hidden has arrived yet.
        val queue = items("a", "b", "c", "d")
        val cast = FakeCastPlayer(receiver(queue, known = setOf(1)))
        val mirror = CastQueueMirror(cast.player)
        mirror.setMediaItems(queue, 1, 0)
        mirror.attach(FakeReceiverQueue(receiverItems(queue)))

        assertEquals(false, mirror.isComplete())
        assertEquals(episodes("a", "b", "c", "d"), mirror.itemsForHandover().map { it.mediaId })
    }

    @Test
    fun aHandoverAfterAMoveNeitherDuplicatesNorDrops() {
        // Sent a, b, c, d; b was moved to the end, and d's slot isn't fetched yet.
        val sent = items("a", "b", "c", "d")
        val now = listOf(Slot(100, sent[0]), Slot(102, sent[2]), Slot(103, null), Slot(101, sent[1]))
        val cast = FakeCastPlayer(ReceiverTimeline(now))
        val mirror = CastQueueMirror(cast.player)
        mirror.setMediaItems(sent, 0, 0)
        mirror.attach(FakeReceiverQueue(emptyMap()))

        assertEquals(episodes("a", "c", "d", "b"), mirror.itemsForHandover().map { it.mediaId })
    }

    @Test
    fun aHandoverDoesNotGuessOnceTheQueueChangedLength() {
        val sent = items("a", "b", "c")
        val cast = FakeCastPlayer(receiver(items("a", "b", "c", "x"), known = setOf(0)))
        val mirror = CastQueueMirror(cast.player)
        mirror.setMediaItems(sent, 0, 0)
        mirror.attach(FakeReceiverQueue(emptyMap()))

        assertEquals(listOf(MediaIds.episode("a"), "", "", ""), mirror.itemsForHandover().map { it.mediaId })
    }

    @Test
    fun aNewQueueForgetsWhatTheOldOnesIdsNamed() {
        // The receiver may reuse ids for a replacement queue within one session.
        val old = items("a", "b", "c")
        val cast = FakeCastPlayer(receiver(old, known = setOf(0)))
        val mirror = CastQueueMirror(cast.player)
        val receiverQueue = FakeReceiverQueue(receiverItems(old))
        mirror.attach(receiverQueue)
        receiverQueue.deliver()

        val new = items("x", "y", "z")
        mirror.setMediaItems(new, 0, 0)
        receiverQueue.items = receiverItems(new)
        cast.changeTimeline(receiver(new, known = setOf(0)))
        receiverQueue.deliver()

        assertEquals(episodes("x", "y", "z"), mirror.currentTimeline.mediaIds())
    }

    @Test
    fun aReloadedQueueIsReadAgain() {
        val old = items("a", "b", "c")
        val cast = FakeCastPlayer(receiver(old, known = setOf(0)))
        val mirror = CastQueueMirror(cast.player)
        val receiverQueue = FakeReceiverQueue(receiverItems(old))
        mirror.attach(receiverQueue)
        receiverQueue.deliver()

        val new = items("a", "y", "z")
        receiverQueue.reload(receiverItems(new))
        receiverQueue.deliver()

        assertEquals(episodes("a", "y", "z"), mirror.currentTimeline.mediaIds())
    }

    @Test
    fun aClearedQueueForgetsItsIds() {
        val old = items("a", "b")
        val cast = FakeCastPlayer(receiver(old, known = setOf(0)))
        val mirror = CastQueueMirror(cast.player)
        val receiverQueue = FakeReceiverQueue(receiverItems(old))
        mirror.attach(receiverQueue)
        receiverQueue.deliver()

        cast.changeTimeline(Timeline.EMPTY)
        val new = items("x", "y")
        receiverQueue.items = receiverItems(new)
        cast.changeTimeline(receiver(new, known = setOf(0)))
        receiverQueue.deliver()

        assertEquals(episodes("x", "y"), mirror.currentTimeline.mediaIds())
    }

    @Test
    fun withoutASessionTheTimelineIsLeftAlone() {
        val raw = receiver(items("a", "b"), known = setOf(0))
        val mirror = CastQueueMirror(FakeCastPlayer(raw).player)
        assertSame(raw, mirror.currentTimeline)
    }

    // ---- listener wrapping ------------------------------------------------------

    private class Recorder : Player.Listener {
        val calls = mutableListOf<String>()
        var timeline: Timeline? = null
        var transitionedTo: MediaItem? = null
        var newPosition: Player.PositionInfo? = null

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            calls += "isPlaying=$isPlaying"
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            calls += "timeline"
            this.timeline = timeline
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            calls += "transition"
            transitionedTo = mediaItem
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            calls += "discontinuity"
            this.newPosition = newPosition
        }
    }

    private fun position(index: Int, uid: Int, item: MediaItem) =
        Player.PositionInfo(uid, index, item, uid, index, 0, 0, C.INDEX_UNSET, C.INDEX_UNSET)

    @Test
    fun theListenerGetsFilledEventsAndEveryOtherEvent() {
        val recorder = Recorder()
        val sent = items("a", "b", "c", "d")
        val raw = receiver(sent, known = setOf(0))
        val filled = receiver(sent, known = setOf(0, 1, 2, 3))
        var currentIndex = 0
        val listener = TimelineFillingListener(recorder, { filled }, { filled }, { currentIndex })

        // Java default methods must reach the delegate, not the interface's no-op.
        listener.onIsPlayingChanged(true)
        listener.onTimelineChanged(raw, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        // A jump to "c", which the receiver hasn't described.
        currentIndex = 2
        listener.onMediaItemTransition(MediaItem.EMPTY, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        listener.onPositionDiscontinuity(
            position(0, 100, sent[0]),
            position(2, 102, MediaItem.EMPTY),
            Player.DISCONTINUITY_REASON_SEEK,
        )

        assertEquals(listOf("isPlaying=true", "timeline", "transition", "discontinuity"), recorder.calls)
        assertEquals(episodes("a", "b", "c", "d"), recorder.timeline!!.mediaIds())
        assertEquals(MediaIds.episode("c"), recorder.transitionedTo!!.mediaId)
        assertEquals(MediaIds.episode("c"), recorder.newPosition!!.mediaItem!!.mediaId)
    }

    @Test
    fun aPositionWhoseWindowMovedIsLeftAlone() {
        val recorder = Recorder()
        val sent = items("a", "b", "c")
        val filled = receiver(sent, known = setOf(0, 1, 2))
        val listener = TimelineFillingListener(recorder, { it }, { filled }, { 0 })

        // Index 2 is no longer the window this position was taken in.
        listener.onPositionDiscontinuity(
            position(0, 100, sent[0]),
            position(2, 999, MediaItem.EMPTY),
            Player.DISCONTINUITY_REASON_REMOVE,
        )

        assertEquals("", recorder.newPosition!!.mediaItem!!.mediaId)
    }

    @Test
    fun removingAListenerRemovesWhatWasAdded() {
        // ForwardingPlayer finds listeners to remove by identity; a fresh wrapper
        // on removal left the progress listener on the Cast player after handing back.
        val added = mutableListOf<Any?>()
        val removed = mutableListOf<Any?>()
        val castPlayer = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "addListener" -> added += args[0]
                "removeListener" -> removed += args[0]
            }
            null
        } as Player
        val mirror = CastQueueMirror(castPlayer)
        val ownListeners = added.size // the mirror's own, registered at construction
        val listener = Recorder()

        mirror.addListener(listener)
        mirror.removeListener(listener)

        assertEquals(ownListeners + 1, added.size)
        assertEquals(listOf(added.last()), removed)
    }

    @Test
    fun everyListenerMethodIsForwarded() {
        // A method left to Player.Listener's default would swallow that event for
        // the whole session while casting: `by delegate` did exactly that.
        val missing = Player.Listener::class.java.methods
            .filter { it.declaringClass == Player.Listener::class.java }
            .filterNot { method ->
                runCatching {
                    TimelineFillingListener::class.java.getDeclaredMethod(method.name, *method.parameterTypes)
                }.isSuccess
            }
            .map { it.name }
        assertEquals(emptyList<String>(), missing)
    }

    // ---- carrying the current episode across -----------------------------------

    @Test
    fun theCurrentEpisodeStaysCurrentWhenEarlierItemsDropOut() {
        val (kept, index) = compactAt(listOf(null, "b", null, "d", "e"), index = 3)
        assertEquals(listOf("b", "d", "e"), kept)
        assertEquals("d", kept[index])
    }

    @Test
    fun aDroppedCurrentItemHandsOverToTheNextOne() {
        val (kept, index) = compactAt(listOf("a", null, "c"), index = 1)
        assertEquals("c", kept[index])
    }

    @Test
    fun aDroppedLastItemFallsBackToTheLastKept() {
        val (kept, index) = compactAt(listOf("a", "b", null), index = 2)
        assertEquals("b", kept[index])
    }

    @Test
    fun anUnsetIndexStartsAtTheFront() {
        val (_, index) = compactAt(listOf("a", "b"), index = C.INDEX_UNSET)
        assertEquals(0, index)
    }
}
