package com.podly

import com.podly.data.db.EpisodeEntity
import com.podly.ui.library.filterEpisodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EpisodeFilterTest {

    private val episodes = listOf(
        episode("money1", "The Economics of Coffee", "Planet Money"),
        episode("daily1", "A Strike in Detroit", "The Daily"),
        episode("hayden1", "第十二課：在咖啡店點飲料", "海頓的中文課"),
        episode("money2", "Detroit's Bankruptcy, Ten Years On", "Planet Money"),
    )

    private fun ids(query: String) = filterEpisodes(episodes, query).map { it.id }

    @Test
    fun blankQueryReturnsTheListUnchanged() {
        assertSame(episodes, filterEpisodes(episodes, ""))
        assertSame(episodes, filterEpisodes(episodes, "   "))
    }

    @Test
    fun everyWordMustMatchInAnyOrderIgnoringCase() {
        assertEquals(listOf("money2"), ids("detroit money"))
        assertEquals(listOf("money2"), ids("  MONEY   Detroit "))
        assertEquals(listOf("daily1", "money2"), ids("detroit"))
    }

    @Test
    fun showNameMatchesEveryEpisodeOfTheShowInListOrder() {
        assertEquals(listOf("money1", "money2"), ids("planet"))
    }

    @Test
    fun chineseMatchesOnTheCharactersTyped() {
        assertEquals(listOf("hayden1"), ids("咖啡"))
        assertEquals(listOf("hayden1"), ids("海頓"))
        // An ideographic space, as a Chinese keyboard types it, separates words too.
        assertEquals(listOf("hayden1"), ids("海頓\u3000咖啡"))
    }

    @Test
    fun anUnmatchedWordExcludesEverything() {
        assertEquals(emptyList<String>(), ids("coffee detroit"))
        assertEquals(emptyList<String>(), ids("zzz"))
    }

    private fun episode(id: String, title: String, show: String) = EpisodeEntity(
        id = id,
        podcastId = show,
        podcastTitle = show,
        guid = id,
        title = title,
        description = null,
        audioUrl = "https://example.com/$id.mp3",
        pubDateMs = 0,
        durationMs = null,
        artworkUrl = null,
    )
}
