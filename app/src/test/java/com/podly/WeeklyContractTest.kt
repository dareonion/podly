package com.podly

import com.podly.data.db.stableId
import com.podly.data.radio.RadioPoolFile
import com.podly.data.weekly.WeeklyIndexFile
import com.podly.data.weekly.WeeklyRepository
import com.podly.network.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weekly digest's half of the wire contract with `tools/radio`
 * (`tests/test_weekly_contract.py` is the other half). Both fixtures are trimmed
 * copies of a real run for 2026-W37, not hand-written, so a renamed field fails
 * here instead of arriving on the phone as an empty week.
 */
class WeeklyContractTest {

    private fun resource(name: String) =
        javaClass.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }

    @Test
    fun `the index lists weeks the app can fetch`() {
        val index = Http.json.decodeFromString<WeeklyIndexFile>(resource("/weekly-index.json"))
        val week = index.issues.single()
        assertEquals("2026-W37", week.id)
        assertTrue(WeeklyRepository.isValidIssueId(week.id))
        assertEquals("2026-W37.json", week.file)
        assertEquals("Sep 7–13, 2026", week.label)
        assertEquals(mapOf("en" to 12, "zh" to 12), week.counts)
        assertEquals(listOf("claude", "codex"), week.pickedBy)
    }

    @Test
    fun `an issue is a playable pool whose reasons are blurbs in each language`() {
        val pool = Http.json.decodeFromString<RadioPoolFile>(resource("/weekly-issue.json"))
        assertEquals("weekly-2026-w37", pool.profileId)
        assertEquals(WeeklyRepository.poolIdFor("2026-W37"), pool.profileId)
        assertTrue(pool.generatedAtMs > 0)

        val (chinese, english) = pool.entries.partition { it.language?.startsWith("zh") == true }
        assertEquals(1, chinese.size)
        assertEquals(1, english.size)
        pool.entries.forEach { entry ->
            assertTrue("audioUrl", entry.episode.audioUrl.startsWith("http"))
            assertTrue("pubDateMs", entry.episode.pubDateMs > 0)
            assertTrue("blurb", !entry.why.isNullOrBlank())
            assertEquals(stableId(entry.episode.guid ?: entry.episode.audioUrl), entry.episode.id)
            assertEquals(stableId(entry.podcast.feedUrl), entry.podcast.id)
            // Genres, which the app files as categories for its content filters.
            assertTrue("tags", entry.tags.isNotEmpty())
        }
        // Blurbs are in the episode's own language.
        assertTrue(chinese.single().why!!.any { it in '一'..'鿿' })
        assertTrue(english.single().why!!.none { it in '一'..'鿿' })
        // Full show notes, not the pool's usual 240-character stub, which would
        // overwrite the real notes of an episode already in the library.
        assertTrue(pool.entries.any { (it.episode.description?.length ?: 0) > 240 })
    }
}
