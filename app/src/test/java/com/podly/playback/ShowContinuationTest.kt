package com.podly.playback

import com.podly.data.db.EpisodeProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShowContinuationTest {

    private val order = listOf(
        EpisodeProgress("e1", completed = true),
        EpisodeProgress("e2", completed = false),
        EpisodeProgress("e3", completed = true),
        EpisodeProgress("e4", completed = false),
        EpisodeProgress("e5", completed = false),
    )

    @Test
    fun playsOnDownTheListSkippingFinishedEpisodes() {
        assertEquals(listOf("e2", "e4", "e5"), continueThroughShow(order, "e2"))
    }

    @Test
    fun theEpisodeTappedPlaysEvenWhenFinished() {
        assertEquals(listOf("e1", "e2", "e4", "e5"), continueThroughShow(order, "e1"))
    }

    @Test
    fun theLastEpisodePlaysAlone() {
        assertEquals(listOf("e5"), continueThroughShow(order, "e5"))
    }

    @Test
    fun anEpisodeNoLongerInTheShowStillPlays() {
        assertEquals(listOf("gone"), continueThroughShow(order, "gone"))
    }

    @Test
    fun theContinuationIsCapped() {
        val long = (1..80).map { EpisodeProgress("e$it", completed = false) }
        val queue = continueThroughShow(long, "e1", limit = 50)
        assertEquals(50, queue.size)
        assertEquals("e50", queue.last())
    }

    @Test
    fun showEpisodeIdsNameTheirEpisodeAndOthersDoNot() {
        assertEquals("abc", MediaIds.episodeIdOrNull(MediaIds.showEpisode("abc")))
        assertEquals("abc", MediaIds.showEpisodeIdOrNull(MediaIds.showEpisode("abc")))
        assertEquals("abc", MediaIds.episodeIdOrNull(MediaIds.episode("abc")))
        assertNull(MediaIds.showEpisodeIdOrNull(MediaIds.episode("abc")))
        assertNull(MediaIds.episodeIdOrNull(MediaIds.podcast("abc")))
        assertNull(MediaIds.episodeIdOrNull(""))
    }
}
