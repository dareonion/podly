package com.podly

import com.podly.data.db.stableId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the id derivation shared with the out-of-repo pool generator
 * (`tools/radio`), which computes the same hashes in Python.
 *
 * If the two ever disagree — a non-UTF-8 encoding on either side would do it —
 * a generated pick inserts a second episode row for something the app already
 * has, and the duplicate is invisible until someone spots the same episode
 * twice. The vectors deliberately include CJK, an emoji and percent-escapes,
 * because those are where an encoding slip would show up first.
 */
class StableIdVectorsTest {

    @Serializable
    private data class Vector(val raw: String, val id: String)

    @Test
    fun `stableId matches the shared vectors`() {
        val json = javaClass.getResourceAsStream("/stable-id-vectors.json")!!
            .bufferedReader().use { it.readText() }
        val vectors = Json.decodeFromString<List<Vector>>(json)
        assertTrue("vectors fixture should not be empty", vectors.isNotEmpty())
        vectors.forEach { vector ->
            assertEquals("stableId(${vector.raw})", vector.id, stableId(vector.raw))
        }
    }
}
