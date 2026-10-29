package com.podly

import com.podly.data.db.PodcastEntity
import com.podly.data.db.SubscribedPodcast
import com.podly.ui.library.SubscriptionSort
import com.podly.ui.library.filterSubscriptions
import com.podly.ui.library.sortName
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.util.Locale

class SubscriptionFilterTest {

    private val collator = Collator.getInstance(Locale.US)

    private val shows = listOf(
        show("planet", "Planet Money", "NPR", lastPlayedAt = 300),
        show("daily", "The Daily", "The New York Times", lastPlayedAt = 0),
        show("hayden", "海頓的中文課", "Hayden", lastPlayedAt = 100),
        show("apple", "apple talk", "Someone", lastPlayedAt = 0),
        show("upfirst", "Up First", "NPR", lastPlayedAt = 400),
    )

    private fun ids(query: String, sort: SubscriptionSort = SubscriptionSort.TITLE) =
        filterSubscriptions(shows, query, sort, collator).map { it.podcast.id }

    @Test
    fun blankQueryKeepsEveryShowInTitleOrderIgnoringCase() {
        assertEquals(listOf("apple", "planet", "daily", "upfirst", "hayden"), ids("  "))
    }

    @Test
    fun nameOrderSkipsLeadingSymbols() {
        val withSymbols = shows + listOf(
            show("reporter", "《The Real Story》 By 報導者", "報導者", lastPlayedAt = 0),
            show("morning", "☀️ 全球串連早安新聞", "浩爾&小路", lastPlayedAt = 0),
        )
        assertEquals(
            listOf("apple", "planet", "daily", "reporter", "upfirst", "morning", "hayden"),
            filterSubscriptions(withSymbols, "", SubscriptionSort.TITLE, collator).map { it.podcast.id },
        )
    }

    @Test
    fun sortNameSkipsSymbolsButKeepsASupplementaryHanCharacter() {
        assertEquals("The Real Story》", sortName("《The Real Story》"))
        assertEquals("全球串連", sortName("☀️ 全球串連"))
        // 𠮷 is a surrogate pair; a per-Char scan would drop it and sort on "野家".
        assertEquals("𠮷野家》", sortName("《𠮷野家》"))
        assertEquals("☀️", sortName("☀️"))
    }

    @Test
    fun matchesTitleOrAuthorCaseInsensitively() {
        assertEquals(listOf("planet", "upfirst"), ids("npr"))
        assertEquals(listOf("planet"), ids("MONEY"))
    }

    @Test
    fun everyWordMustMatchInAnyOrder() {
        assertEquals(listOf("planet"), ids("money planet"))
        assertEquals(listOf("planet"), ids("npr money"))
        assertEquals(emptyList<String>(), ids("money daily"))
    }

    @Test
    fun chineseMatchesOnTheCharactersTyped() {
        assertEquals(listOf("hayden"), ids("中文"))
        assertEquals(emptyList<String>(), ids("中国"))
    }

    @Test
    fun recentlyPlayedPutsNeverPlayedShowsLastAlphabetically() {
        assertEquals(
            listOf("upfirst", "planet", "hayden", "apple", "daily"),
            ids("", SubscriptionSort.RECENT),
        )
    }

    @Test
    fun recentlyPlayedAppliesTheFilterToo() {
        assertEquals(listOf("upfirst", "planet"), ids("npr", SubscriptionSort.RECENT))
    }

    private fun show(id: String, title: String, author: String, lastPlayedAt: Long) = SubscribedPodcast(
        podcast = PodcastEntity(
            id = id,
            title = title,
            author = author,
            feedUrl = "https://example.com/$id.xml",
            artworkUrl = null,
            description = null,
            subscribed = true,
        ),
        lastPlayedAt = lastPlayedAt,
    )
}
