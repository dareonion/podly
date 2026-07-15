package com.podly

import com.podly.data.PicksImportFile
import com.podly.network.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the picks-import file shape ([PicksImportFile]): the picks reuse the wire
 * shape of the generator's recent-episodes files, plus a top-level playlist name.
 * Unknown fields (e.g. per-pick confidence from an exported list) must be ignored.
 */
class PicksImportContractTest {

    private fun load(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }

    @Test
    fun `import file deserializes with name and resolved picks in order`() {
        val file = Http.json.decodeFromString<PicksImportFile>(load("picks-import.json"))

        assertEquals("ChatGPT picks · 2026-07-15", file.name)
        assertEquals(1, file.version)
        assertEquals(1752566400000L, file.generatedAtMs)
        assertEquals(2, file.picks.size)

        val resolved = file.picks[0]
        assertEquals("The Test Case", resolved.pick.episodeTitle)
        assertEquals("2026-07-13", resolved.pick.publishedApprox)
        val podcast = resolved.toPodcastOrNull()
        assertNotNull(podcast)
        assertEquals("https://www.thisamericanlife.org/podcast/rss.xml", podcast!!.feedUrl)

        // A pick with no pre-resolved feed carries no podcast; the importer falls
        // back to the iTunes directory for these.
        assertNull(file.picks[1].toPodcastOrNull())
        assertEquals("Some Unresolved Show", file.picks[1].pick.podcastTitle)
    }
}
