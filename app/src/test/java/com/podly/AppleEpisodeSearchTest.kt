package com.podly

import com.podly.data.chat.AppleEpisodeSearch
import com.podly.network.AppleEpisode
import com.podly.network.ItunesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An episode found by Apple's search only counts when it is plainly the chat's. */
class AppleEpisodeSearchTest {

    private fun resource(name: String) =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().readText()

    // Apple's real answer to "大陳島撤退後，他們去哪了？" (2026-10-29): one episode, of
    // 天下雜誌's umbrella feed, where 微笑台灣 is a weekly segment.
    private val results = ItunesApi.parseEpisodes(resource("apple-search-episodes.json"))
    private val show = "微笑台灣 Podcast"
    private val core = "大陳島撤退後，他們去哪了？"

    private fun other(title: String, showId: String, showName: String) = AppleEpisode(
        trackId = "t-$showId", title = title, releaseDateMs = 0, guid = "g-$showId", audioUrl = "https://example.com/a.mp3",
        durationMs = null, description = null, artworkUrl = null, collectionId = showId, collectionName = showName,
    )

    @Test
    fun `a search result carries its show and GUID`() {
        val hit = results.single()
        assertEquals("1486227803", hit.collectionId)
        assertEquals("聽天下：天下雜誌Podcast", hit.collectionName)
        assertEquals("32e89b1d-1c0d-43ac-a54d-088bfe301cf5", hit.guid)
    }

    @Test
    fun `the chat's show name comes off the front of the title`() {
        assertEquals(core, AppleEpisodeSearch.core("微笑台灣 Podcast：大陳島撤退後，他們去哪了？", show))
        assertEquals(core, AppleEpisodeSearch.core("《微笑台灣 Podcast》：大陳島撤退後，他們去哪了？", show))
        assertNull(AppleEpisodeSearch.core("微笑台灣 Podcast：", show))
    }

    @Test
    fun `a segment's episode is found by its tag inside another show's feed`() {
        val hit = AppleEpisodeSearch.pick(results, core, show, null)
        assertEquals("【微笑台灣Ep.192】大陳島撤退後，他們去哪了？曾經最北的離島，從年糕、鰻鮝聽一段大陳故事", hit?.title)
    }

    @Test
    fun `the same title without the show's name is not the episode`() {
        val lookalike = other("大陳島撤退後，他們去哪了？", "999", "離島故事集")
        assertNull(AppleEpisodeSearch.pick(listOf(lookalike), core, show, null))
    }

    @Test
    fun `two shows carrying it is too many to choose from`() {
        val second = other("【微笑台灣】大陳島撤退後，他們去哪了？", "888", "微笑台灣精選")
        assertNull(AppleEpisodeSearch.pick(results + second, core, show, null))
    }

    @Test
    fun `unrelated results Apple returns for a show it doesn't have are rejected`() {
        // Apple's real top hits for 唠得默默's "弦子与她的朋友们", a show it doesn't list.
        val junk = listOf(
            other("九星霸体诀 第571到580章", "1", "九星霸体诀"),
            other("九星霸体诀 第1561到1570章", "1", "九星霸体诀"),
        )
        assertNull(AppleEpisodeSearch.pick(junk, "弦子与她的朋友们", "唠得默默", null))
    }

    @Test
    fun `a show sharing one word of the name is another show`() {
        val other = other("The cost of war", "777", "Daily Tech News Show")
        assertNull(AppleEpisodeSearch.pick(listOf(other), "The cost of war", "The Daily", null))
        // Nor does a longer word that merely starts with the name count.
        val tagged = other("Dailyness: The cost of war", "776", "Weekly Digest")
        assertNull(AppleEpisodeSearch.pick(listOf(tagged), "The cost of war", "Daily", null))
    }

    @Test
    fun `a sibling episode sharing most of the title is not the episode`() {
        val pixar = other("How Steve Jobs built Pixar", "555", "Founders")
        assertNull(AppleEpisodeSearch.pick(listOf(pixar), "How Steve Jobs built Apple", "Founders", null))
        val apple = other("#123 How Steve Jobs built Apple", "555", "Founders")
        assertEquals(apple, AppleEpisodeSearch.pick(listOf(pixar, apple), "How Steve Jobs built Apple", "Founders", null))
    }

    @Test
    fun `short words tell episodes apart`() {
        val vr = other("The economics of VR", "444", "Planet Money")
        assertNull(AppleEpisodeSearch.pick(listOf(vr), "The economics of AI", "Planet Money", null))
        val ai = other("AI", "443", "Planet Money")
        assertEquals(ai, AppleEpisodeSearch.pick(listOf(ai), "AI", "Planet Money", null))
    }

    @Test
    fun `episode markers are written many ways`() {
        val hit = other("Ep.268 Deep ocean warming", "333", "Ocean Hour")
        assertEquals(hit, AppleEpisodeSearch.pick(listOf(hit), "Ep268 Deep ocean warming", "Ocean Hour", null))
        assertEquals(setOf("268", "deep"), AppleEpisodeSearch.words("EP0268 Deep"))
        // Another episode number is another episode.
        val other = other("Ep.269 Deep ocean warming", "333", "Ocean Hour")
        assertNull(AppleEpisodeSearch.pick(listOf(other), "Ep268 Deep ocean warming", "Ocean Hour", null))
    }

    @Test
    fun `an accented name inside a longer word is another show`() {
        val hit = other("Morning roast", "222", "Cafés de Colombia")
        assertNull(AppleEpisodeSearch.pick(listOf(hit), "Morning roast", "Café", null))
    }

    @Test
    fun `several editions holding the title are told apart by date, or not at all`() {
        fun edition(year: Int) = other("Ocean warming explained ($year edition)", "111", "Ocean Hour").copy(
            trackId = "t$year", guid = "g$year", releaseDateMs = java.time.LocalDate.of(year, 6, 1).toEpochDay() * 86_400_000L,
        )
        val editions = (2022..2026).map(::edition)
        assertEquals(edition(2025), AppleEpisodeSearch.pick(editions, "Ocean warming explained", "Ocean Hour", "2025-06-03"))
        assertNull(AppleEpisodeSearch.pick(editions, "Ocean warming explained", "Ocean Hour", null))
    }

    @Test
    fun `a short title is found however it is numbered or tagged`() {
        val numbered = other("#123 AI", "442", "Planet Money")
        assertEquals(numbered, AppleEpisodeSearch.pick(listOf(numbered), "AI", "Planet Money", null))
        val tagged = other("【Planet Money】AI", "441", "NPR Umbrella")
        assertEquals(tagged, AppleEpisodeSearch.pick(listOf(tagged), "AI", "Planet Money", null))
    }

    @Test
    fun `marker words count as words when no number follows`() {
        val country = other("Country for old men", "440", "Film Hour")
        assertNull(AppleEpisodeSearch.pick(listOf(country), "No country for old men", "Film Hour", null))
        val hepB = other("Hepatitis B", "439", "Health Hour")
        assertNull(AppleEpisodeSearch.pick(listOf(hepB), "Hepatitis E", "Health Hour", null))
    }

    @Test
    fun `a show name is only taken off a title's front as a whole word`() {
        assertEquals("Storytelling explained", AppleEpisodeSearch.core("Storytelling explained", "Story"))
    }

    @Test
    fun `an opening word is a tag only when set off as one`() {
        val prose = other("Science explains how black holes form", "438", "Other Feed")
        assertNull(AppleEpisodeSearch.pick(listOf(prose), "How black holes form", "Science", null))
        val tag = other("Science: How black holes form", "438", "Other Feed")
        assertEquals(tag, AppleEpisodeSearch.pick(listOf(tag), "How black holes form", "Science", null))
    }

    @Test
    fun `a date the link gave rules out reruns and leaves same-day twins undecided`() {
        val dayMs = java.time.LocalDate.of(2023, 3, 1).toEpochDay() * 86_400_000L
        val rerun = other("Ocean warming explained", "437", "Ocean Hour").copy(releaseDateMs = dayMs + 400 * 86_400_000L)
        assertNull(AppleEpisodeSearch.pick(listOf(rerun), "Ocean warming explained", "Ocean Hour", "2023-03-01"))
        val morning = other("Ocean warming explained (morning)", "436", "Ocean Hour")
            .copy(trackId = "am", guid = "am", releaseDateMs = dayMs + 8 * 3_600_000L)
        val evening = morning.copy(title = "Ocean warming explained (evening)", trackId = "pm", guid = "pm", releaseDateMs = dayMs + 16 * 3_600_000L)
        assertNull(AppleEpisodeSearch.pick(listOf(morning, evening), "Ocean warming explained", "Ocean Hour", "2023-03-01"))
    }

    @Test
    fun `the show's name in a title is no word of the episode's`() {
        val japan = other("【微笑台灣Ep.193】日本：移民的故事", "1486227803", "聽天下：天下雜誌Podcast")
        assertNull(AppleEpisodeSearch.pick(listOf(japan), "台灣：移民的故事", show, null))
        val habit = other("【Planet Money】The power of habit", "432", "Planet Money")
        assertNull(AppleEpisodeSearch.pick(listOf(habit), "The power of money", "Planet Money", null))
    }

    @Test
    fun `an episode that names another one's number is not that one`() {
        val reply = other("【微笑台灣Ep.193】Ep.192〈大陳島撤退後，他們去哪了？〉聽眾回應", "1486227803", "聽天下：天下雜誌Podcast")
        assertNull(AppleEpisodeSearch.pick(listOf(reply), "Ep192：大陳島撤退後，他們去哪了？", show, null))
        val later = other("第193集 台灣故事：192個回憶", "431", "台灣故事")
        assertNull(AppleEpisodeSearch.pick(listOf(later), "第192集 台灣故事", "台灣故事", null))
        val ocean = other("【Ocean Hour Ep.269】Deep ocean warming in 268 regions", "430", "Ocean Hour")
        assertNull(AppleEpisodeSearch.pick(listOf(ocean), "Episode 268 Deep ocean warming", "Ocean Hour", null))
    }

    @Test
    fun `a letter before punctuation and a number is a word, not a marker`() {
        val vitaminD = other("維他命 D：5 個你該知道的好處", "429", "健康你我他")
        assertNull(AppleEpisodeSearch.pick(listOf(vitaminD), "維他命 E：5 個你該知道的好處", "健康你我他", null))
        val hepB = other("Hepatitis B (2024)", "428", "Health Hour")
        assertNull(AppleEpisodeSearch.pick(listOf(hepB), "Hepatitis E (2024)", "Health Hour", null))
    }

    @Test
    fun `a title that merely starts with the show's name keeps it`() {
        assertEquals("故事的結局", AppleEpisodeSearch.core("故事的結局", "故事"))
        assertEquals("History of China", AppleEpisodeSearch.core("History of China", "History"))
        assertEquals("History of China", AppleEpisodeSearch.core("History - History of China", "History"))
    }

    @Test
    fun `a result without a GUID is skipped`() {
        val noGuid = results.single().copy(guid = null)
        assertNull(AppleEpisodeSearch.pick(listOf(noGuid), core, show, null))
    }
}
