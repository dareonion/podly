package com.podly.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePlacementTest {

    private val queue = listOf("a", "b", "c", "d")

    @Test
    fun emptyQueueInsertsAtStart() {
        assertEquals(QueuePlacement.Insert(0), queuePlacement(emptyList(), 0, "x", playNext = true))
        assertEquals(QueuePlacement.Insert(0), queuePlacement(emptyList(), 0, "x", playNext = false))
    }

    @Test
    fun newEpisodeGoesAfterCurrentOrAtEnd() {
        assertEquals(QueuePlacement.Insert(2), queuePlacement(queue, 1, "x", playNext = true))
        assertEquals(QueuePlacement.Insert(4), queuePlacement(queue, 1, "x", playNext = false))
    }

    @Test
    fun playNextOnLastItemAppends() {
        assertEquals(QueuePlacement.Insert(4), queuePlacement(queue, 3, "x", playNext = true))
    }

    @Test
    fun currentEpisodeIsNotQueuedAgain() {
        assertEquals(QueuePlacement.AlreadyPlaying, queuePlacement(queue, 1, "b", playNext = true))
        assertEquals(QueuePlacement.AlreadyPlaying, queuePlacement(queue, 1, "b", playNext = false))
    }

    @Test
    fun playNextMovesAnUpcomingEpisodeUp() {
        // [a, B, c, d] -> [a, B, d, c]
        assertEquals(QueuePlacement.Move(3, 2), queuePlacement(queue, 1, "d", playNext = true))
    }

    @Test
    fun playNextMovesAPlayedEpisodeToJustAfterCurrent() {
        // [a, b, C, d] -> [b, C, a, d]: removing "a" shifts C to index 1, so "a" lands at 2.
        assertEquals(QueuePlacement.Move(0, 2), queuePlacement(queue, 2, "a", playNext = true))
    }

    @Test
    fun addToQueueLeavesAnUpcomingEpisodeAlone() {
        assertEquals(QueuePlacement.AlreadyQueued, queuePlacement(queue, 1, "c", playNext = false))
    }

    @Test
    fun addToQueueRequeuesAPlayedEpisodeAtTheEnd() {
        assertEquals(QueuePlacement.Move(0, 3), queuePlacement(queue, 2, "a", playNext = false))
    }

    @Test
    fun upcomingCopyWinsOverAPlayedOne() {
        val withRepeat = listOf("a", "b", "c", "a")
        assertEquals(QueuePlacement.AlreadyQueued, queuePlacement(withRepeat, 1, "a", playNext = false))
        assertEquals(QueuePlacement.Move(3, 2), queuePlacement(withRepeat, 1, "a", playNext = true))
    }

    @Test
    fun outOfRangeCurrentIndexIsClamped() {
        assertEquals(QueuePlacement.Insert(4), queuePlacement(queue, 9, "x", playNext = true))
    }
}
