package com.podly

import com.podly.data.PlaylistRepository
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSortTest {

    private fun episode(id: String, pubDateMs: Long) = EpisodeEntity(
        id = id,
        podcastId = "pod",
        podcastTitle = "Pod",
        guid = id,
        title = "Episode $id",
        description = null,
        audioUrl = "https://example.com/$id.mp3",
        pubDateMs = pubDateMs,
        durationMs = null,
        artworkUrl = null,
    )

    // Manual order (by playlist position) intentionally differs from chronology.
    private val manualOrder = listOf(
        episode("b", 200),
        episode("c", 300),
        episode("a", 100),
    )

    @Test
    fun `manual keeps stored order`() {
        val sorted = PlaylistRepository.applySort(SortMode.MANUAL, manualOrder)
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun `chrono asc sorts oldest first`() {
        val sorted = PlaylistRepository.applySort(SortMode.CHRONO_ASC, manualOrder)
        assertEquals(listOf("a", "b", "c"), sorted.map { it.id })
    }

    @Test
    fun `chrono desc sorts newest first`() {
        val sorted = PlaylistRepository.applySort(SortMode.CHRONO_DESC, manualOrder)
        assertEquals(listOf("c", "b", "a"), sorted.map { it.id })
    }
}
