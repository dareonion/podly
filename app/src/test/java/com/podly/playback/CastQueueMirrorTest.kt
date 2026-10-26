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

    /** Sent while the receiver showed nothing yet. */
    private fun sentQueue(sent: List<MediaItem>) = SentQueue().apply { set(Timeline.EMPTY, sent) }

    // ---- filling ----------------------------------------------------------------

    @Test
    fun fillsTheItemsTheReceiverHasNotDescribed() {
        val sent = items("a", "b", "c", "d", "e")
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = setOf(1, 2, 3))

        val filled = queue.fill(raw)

        assertEquals(episodes("a", "b", "c", "d", "e"), filled.mediaIds())
        // Structure is the receiver's: the uids still address its queue items.
        assertEquals(100, filled.getWindow(0, Timeline.Window()).uid)
        assertEquals(raw.periodCount, filled.periodCount)
    }

    @Test
    fun aDifferentLengthLeavesNewIdsUnfilled() {
        val queue = sentQueue(items("a", "b", "c"))
        val raw = receiver(items("a", "b", "c", "d"), known = setOf(0))
        assertSame(raw, queue.fill(raw))
    }

    @Test
    fun aDescribedItemThatDisagreesLeavesNewIdsUnfilled() {
        val queue = sentQueue(items("a", "b", "c"))
        // Another sender's queue of the same length.
        val raw = receiver(items("b", "a", "c"), known = setOf(0))
        assertSame(raw, queue.fill(raw))
    }

    @Test
    fun aFullyDescribedTimelineIsLeftAlone() {
        val sent = items("a", "b")
        val raw = receiver(sent, known = setOf(0, 1))
        assertSame(raw, sentQueue(sent).fill(raw))
    }

    @Test
    fun nothingSentLeavesTheTimelineAlone() {
        val raw = receiver(items("a", "b"), known = setOf(0))
        assertSame(raw, SentQueue().fill(raw))
    }

    // ---- changes the receiver has not confirmed ----------------------------------

    @Test
    fun aRejectedMoveOfHiddenItemsRelabelsNothing() {
        val sent = items("a", "b", "c", "d", "e", "f")
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = setOf(0, 1))
        queue.fill(raw)

        // Move "d" after "e"; the receiver keeps its order (rejected, or not yet applied).
        queue.move(raw, 3, 4, 4)

        assertEquals(episodes("a", "b", "c", "d", "e", "f"), queue.fill(raw).mediaIds())
    }

    @Test
    fun aConfirmedMoveIsFollowedByItemId() {
        val sent = items("a", "b", "c", "d", "e")
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = setOf(0))
        queue.fill(raw)
        queue.move(raw, 3, 4, 4)

        // The receiver now holds d (id 103) after e (id 104).
        val moved = ReceiverTimeline(
            listOf(Slot(100, sent[0]), Slot(101, null), Slot(102, null), Slot(104, null), Slot(103, null)),
        )
        assertEquals(episodes("a", "b", "c", "e", "d"), queue.fill(moved).mediaIds())
    }

    @Test
    fun anAddedItemIsMatchedWhenItsIdAppears() {
        val sent = items("a", "b", "c")
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = setOf(0))
        queue.fill(raw)

        queue.add(raw, 1, items("x"))
        // Not confirmed yet: nothing new to match.
        assertEquals(episodes("a", "b", "c"), queue.fill(raw).mediaIds())

        val confirmed = ReceiverTimeline(
            listOf(Slot(100, sent[0]), Slot(103, null), Slot(101, null), Slot(102, null)),
        )
        assertEquals(episodes("a", "x", "b", "c"), queue.fill(confirmed).mediaIds())
    }

    @Test
    fun twoAppendsBeforeTheReceiverAnswersKeepTheirOrder() {
        // Both resolve against the confirmed three-item queue, so both append.
        val sent = items("a", "b", "c")
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = setOf(0))
        queue.fill(raw)

        queue.add(raw, 3, items("x"))
        queue.add(raw, 3, items("y"))

        val confirmed = ReceiverTimeline(
            listOf(Slot(100, sent[0]), Slot(101, null), Slot(102, null), Slot(103, null), Slot(104, null)),
        )
        assertEquals(episodes("a", "b", "c", "x", "y"), queue.fill(confirmed).mediaIds())
    }

    @Test
    fun twoPlayNextsBeforeTheReceiverAnswersStackBeforeTheSameItem() {
        // Each is sent as "insert before b": the receiver ends up with a, x, y, b, c.
        val sent = items("a", "b", "c")
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = setOf(0))
        queue.fill(raw)

        queue.add(raw, 1, items("x"))
        queue.add(raw, 1, items("y"))

        val confirmed = ReceiverTimeline(
            listOf(Slot(100, sent[0]), Slot(103, null), Slot(104, null), Slot(101, null), Slot(102, null)),
        )
        assertEquals(episodes("a", "x", "y", "b", "c"), queue.fill(confirmed).mediaIds())
    }

    @Test
    fun aMoveNotYetConfirmedThenAnAddAreBothFollowed() {
        val sent = items("a", "b", "c", "d", "e")
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = setOf(0))
        queue.fill(raw)

        queue.move(raw, 3, 4, 4)
        queue.fill(raw) // an ordinary read while the move is in flight
        queue.add(raw, 5, items("x"))

        val confirmed = ReceiverTimeline(
            listOf(100, 101, 102, 104, 103, 105).map { Slot(it, if (it == 100) sent[0] else null) },
        )
        assertEquals(episodes("a", "b", "c", "e", "d", "x"), queue.fill(confirmed).mediaIds())
    }

    @Test
    fun aRejectedMoveDoesNotBlockLaterAdds() {
        val sent = items("a", "b", "c", "d", "e")
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = setOf(0))
        queue.fill(raw)
        queue.move(raw, 3, 4, 4) // never applied by the receiver
        queue.fill(raw)

        queue.add(raw, 5, items("x"))
        val confirmed = ReceiverTimeline((100..105).map { Slot(it, if (it == 100) sent[0] else null) })
        assertEquals(episodes("a", "b", "c", "d", "e", "x"), queue.fill(confirmed).mediaIds())
    }

    @Test
    fun pendingInsertsFollowAConfirmedMove() {
        // Move d to the front, then insert x before b and y before d, all in flight.
        val sent = items("a", "b", "c", "d")
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = emptySet())
        queue.fill(raw)

        queue.move(raw, 3, 4, 0)
        queue.add(raw, 1, items("x"))
        queue.add(raw, 3, items("y"))

        // The receiver: d, a, b, c; then x before b; then y before d.
        val confirmed = ReceiverTimeline(listOf(105, 103, 100, 104, 101, 102).map { Slot(it, null) })
        assertEquals(episodes("y", "d", "a", "x", "b", "c"), queue.fill(confirmed).mediaIds())
    }

    @Test
    fun anotherSendersReplacementNeverTakesTheRemovedItemsIdentity() {
        val sent = items("a", "b", "c", "d", "e")
        val queue = sentQueue(sent)
        queue.fill(receiver(sent, known = setOf(0, 1)))

        // Someone else swaps hidden d (103) for their own item, id 105.
        val replaced = ReceiverTimeline(
            listOf(Slot(100, sent[0]), Slot(101, sent[1]), Slot(102, null), Slot(105, null), Slot(104, null)),
        )
        assertEquals(
            listOf(MediaIds.episode("a"), MediaIds.episode("b"), MediaIds.episode("c"), "", MediaIds.episode("e")),
            queue.fill(replaced).mediaIds(),
        )
    }

    @Test
    fun anItemFromAnotherSenderIsNeverGivenOurs() {
        val sent = items("a", "b", "c")
        val queue = sentQueue(sent)
        queue.fill(receiver(sent, known = setOf(0)))

        // Another sender appends z (103) while nothing of ours is waiting...
        val withTheirs = ReceiverTimeline((100..103).map { Slot(it, if (it == 100) sent[0] else null) })
        queue.fill(withTheirs)
        // ...then we append x, which arrives as 104.
        queue.add(withTheirs, 4, items("x"))
        val withOurs = ReceiverTimeline((100..104).map { Slot(it, if (it == 100) sent[0] else null) })

        assertEquals(
            episodes("a", "b", "c").plus("").plus(MediaIds.episode("x")),
            queue.fill(withOurs).mediaIds(),
        )
    }

    @Test
    fun aNewQueueDoesNotRelabelTheOldOneStillShowing() {
        val old = items("a", "b", "c")
        val queue = sentQueue(old)
        val oldRaw = receiver(old, known = setOf(0))
        queue.fill(oldRaw)

        val new = items("x", "y", "z")
        queue.set(oldRaw, new)
        // The receiver hasn't loaded the new queue yet: same length, old ids.
        assertEquals(episodes("a", "b", "c"), queue.fill(oldRaw).mediaIds())
        // Then it does, under new ids.
        val loaded = receiver(new, known = setOf(0), uids = listOf(200, 201, 202))
        assertEquals(episodes("x", "y", "z"), queue.fill(loaded).mediaIds())
    }

    // ---- keeping the record in step with RemoteCastPlayer ------------------------

    @Test
    fun anEmptySetIsIgnoredLikeRemoteCastPlayerDoes() {
        val sent = items("a", "b")
        val queue = sentQueue(sent)
        queue.set(Timeline.EMPTY, emptyList())
        assertEquals(episodes("a", "b"), queue.fill(receiver(sent, known = emptySet())).mediaIds())
    }

    @Test
    fun removeAndReplaceAreRecordedById() {
        val sent = items("a", "b", "c", "d")
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = emptySet())
        queue.fill(raw)

        queue.remove(raw, 1, 2) // drops b (101)
        queue.replace(raw, 2, 3, items("x", "y")) // inserts before d (103), drops c (102)

        val confirmed = ReceiverTimeline(listOf(Slot(100, null), Slot(104, null), Slot(105, null), Slot(103, null)))
        assertEquals(episodes("a", "x", "y", "d"), queue.fill(confirmed).mediaIds())
    }

    @Test
    fun removeClampsToTheEnd() {
        val sent = items("a", "b", "c")
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = emptySet())
        queue.fill(raw)
        queue.remove(raw, 1, Int.MAX_VALUE)
        queue.add(raw, Int.MAX_VALUE, items("x"))
        val confirmed = ReceiverTimeline(listOf(Slot(100, null), Slot(103, null)))
        assertEquals(episodes("a", "x"), queue.fill(confirmed).mediaIds())
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
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = setOf(0))
        var currentIndex = 0
        val listener = TimelineFillingListener(recorder, queue::fill, { queue.fill(raw) }, { currentIndex })

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
        val queue = sentQueue(sent)
        val raw = receiver(sent, known = setOf(0))
        val listener = TimelineFillingListener(recorder, queue::fill, { queue.fill(raw) }, { 0 })

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
        val listener = Recorder()

        mirror.addListener(listener)
        mirror.removeListener(listener)

        assertEquals(1, added.size)
        assertEquals(added, removed)
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
