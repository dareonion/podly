package com.podly.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the batched episode lookup behind row play, the Cast handover and
 * playback resumption: a Library queue of thousands, one query each, kept
 * playback from starting for about 30 seconds.
 */
class EpisodeLookupTest {

    private data class Row(val id: String)

    /** A table holding [present], recording each query it is asked. */
    private class Table(present: Collection<String>) {
        private val rows = present.associateWith(::Row)
        val queries = mutableListOf<List<String>>()

        // Like SQL's IN: rows in table order, not the order asked.
        suspend fun fetch(ids: List<String>): List<Row> {
            queries += ids
            return rows.values.filter { it.id in ids }
        }
    }

    @Test
    fun rowsComeBackInTheOrderAsked() = runBlocking {
        val table = Table(listOf("a", "b", "c"))
        val rows = lookUpInOrder(listOf("c", "a", "b"), Row::id, fetch = table::fetch)
        assertEquals(listOf("c", "a", "b"), rows.map { it?.id })
    }

    @Test
    fun missingAndNullIdsKeepTheirPlaces() = runBlocking {
        // compactAt counts around them, so the current episode stays current.
        val table = Table(listOf("a", "c"))
        val rows = lookUpInOrder(listOf("a", null, "gone", "c"), Row::id, fetch = table::fetch)
        assertEquals(listOf("a", null, null, "c"), rows.map { it?.id })
    }

    @Test
    fun aRepeatedIdIsAskedOnceAndFilledEachTime() = runBlocking {
        val table = Table(listOf("a", "b"))
        val rows = lookUpInOrder(listOf("a", "b", "a"), Row::id, fetch = table::fetch)
        assertEquals(listOf("a", "b", "a"), rows.map { it?.id })
        assertEquals(listOf(listOf("a", "b")), table.queries)
    }

    @Test
    fun aLongQueueIsAskedInBatches() = runBlocking {
        val ids = (0 until 1_234).map { "ep$it" }
        val table = Table(ids)
        val rows = lookUpInOrder(ids, Row::id, batch = 500, fetch = table::fetch)
        assertEquals(ids, rows.map { it?.id })
        assertEquals(listOf(500, 500, 234), table.queries.map { it.size })
        assertTrue(table.queries.all { it.size <= EPISODE_LOOKUP_BATCH })
    }

    @Test
    fun nothingToLookUpAsksNothing() = runBlocking {
        val table = Table(emptyList())
        val rows = lookUpInOrder(listOf(null, null), Row::id, fetch = table::fetch)
        assertEquals(listOf(null, null), rows)
        assertEquals(emptyList<List<String>>(), table.queries)
    }
}
