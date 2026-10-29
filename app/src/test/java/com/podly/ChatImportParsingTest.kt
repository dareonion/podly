package com.podly

import com.podly.data.chat.ChatLink
import com.podly.data.chat.ChatPick
import com.podly.data.chat.ChatPickExtractor
import com.podly.data.chat.ChatSharePage
import com.podly.network.ItunesApi
import com.podly.network.XiaoyuzhouApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Against a real ChatGPT chat (中文內容推薦, shared 2026-10-28): its two answers,
 * trimmed to the assistant's messages and their link bindings and re-encoded in the
 * page's own stream format (the prompts are left out; this repo is public). The
 * full 1.8 MB page gave the same 91 picks when this was written.
 */
class ChatImportParsingTest {

    private fun resource(name: String) =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().readText()

    private val chat = ChatSharePage.parse(resource("chatgpt-share-zh-recs.html"))
    private val picks = chat.messages.flatMap(ChatPickExtractor::extract)
    private fun pick(title: String): ChatPick = picks.first { it.title == title }

    @Test
    fun `the share page decodes into the chat's answers and their links`() {
        assertEquals("中文內容推薦", chat.title)
        assertEquals(7, chat.messages.size) // two answers plus five progress notes
        assertEquals(63, chat.messages.sumOf { it.links.size })
        assertEquals(91, picks.size)
    }

    @Test
    fun `a numbered episode keeps its link and reason, without the year and length`() {
        val p = pick("E468 我是来自河南的犹太人")
        assertFalse(p.isShow)
        assertTrue(p.url!!.contains("xiaoyuzhoufm.com/episode/602fa117139c2fe28f504d45"))
        assertTrue(p.reason!!.startsWith("A woman from Kaifeng explores her Jewish heritage"))
    }

    @Test
    fun `two episodes sharing one show link stay two episodes of that show`() {
        val a = pick("說故事的人：我走的時候，不要哭")
        val b = pick("說故事的人：一生只做一件事")
        assertEquals(a.url, b.url)
        assertEquals(ChatLink.AppleShow("1553424581"), ChatLink.parse(a.url!!))
        assertFalse(a.isShow)
        assertEquals("說故事的人", a.showName)
    }

    @Test
    fun `an episode with no link is found by its show's name`() {
        val p = pick("过刊 EP20：你们忘了那扇门")
        assertNull(p.url)
        assertFalse(p.isShow)
        assertEquals("过刊", p.showName)
    }

    @Test
    fun `a series card gives one pick per linked part`() {
        val parts = picks.filter { it.title.startsWith("故事FM — 《那些被喜悦暴击的时刻》") }
        assertEquals(listOf("E357 Original", "E489 Part 2", "E591 Part 3"), parts.map { it.title.substringAfter("》 ") })
        assertTrue(ChatLink.parse(parts[0].url!!) is ChatLink.AppleEpisode)
        assertEquals(ChatLink.XiaoyuzhouEpisode("6074171ea8969a63cb6e6a01"), ChatLink.parse(parts[1].url!!))
        assertTrue(parts.all { it.showName == "故事FM" && it.reason!!.startsWith("People recount") })
    }

    @Test
    fun `a card with a Browse link is the show, colon and all`() {
        val p = pick("解鎖地球：旅行、歷史、文化")
        assertTrue(p.isShow)
        assertEquals(ChatLink.AppleShow("1476564589"), ChatLink.parse(p.url!!))
        assertTrue(pick("日谈公园").isShow)
    }

    @Test
    fun `a starting-queue row takes its button's link and caption`() {
        val p = pick("故事FM — E357 那些被喜悦暴击的时刻")
        assertEquals(picks.first { it.title.endsWith("E357 Original") }.url, p.url)
        assertEquals("Best pure feel-good storytelling", p.reason)
        assertEquals("故事FM", p.showName)
    }

    @Test
    fun `table rows give their first cell as the title and the next as the reason`() {
        val p = pick("旅行熱炒店 EP270：Amish 之謎")
        assertEquals("https://ltsoj.com/podcast-ep270", p.url)
        assertEquals("旅行熱炒店", p.showName)
        val show = pick("The Real Story by 報導者")
        assertTrue(show.isShow)
        assertEquals("Investigative reporting", show.reason)
    }

    @Test
    fun `plain Markdown answers work too`() {
        val text = """
            Some picks:

            1. [E468.我是来自河南的犹太人](https://www.xiaoyuzhoufm.com/episode/602fa117139c2fe28f504d45) — A woman from Kaifeng.
            2. **說故事的人：逃亡的人** (2021): https://podcasts.apple.com/tw/podcast/x/id1553424581?i=1000523583667
            - Not a recommendation, just a closing line.
        """.trimIndent()
        val found = ChatPickExtractor.extract(ChatSharePage.Message(text, emptyMap()))
        assertEquals(2, found.size)
        assertEquals("E468.我是来自河南的犹太人", found[0].title)
        assertEquals("A woman from Kaifeng.", found[0].reason)
        assertEquals(ChatLink.AppleEpisode("1553424581", "1000523583667"), ChatLink.parse(found[1].url!!))
    }

    @Test
    fun `show names split off episode titles, but episode numbers are not shows`() {
        assertEquals("说故事的人" to "说故事的人 EP8：疫情下的人", ChatPickExtractor.splitShow("说故事的人 EP8：疫情下的人"))
        assertEquals("忽左忽右", ChatPickExtractor.splitShow("忽左忽右 282：基辅无战事")?.first)
        assertEquals("故事FM", ChatPickExtractor.splitShow("故事FM — E357 那些被喜悦暴击的时刻")?.first)
        assertNull(ChatPickExtractor.splitShow("Vol.05 逃出妙瓦底：电诈集中营亲历者访谈"))
        assertNull(ChatPickExtractor.splitShow("282 基辅无战事：吕晓宇的乌克兰亲历记"))
        assertNull(ChatPickExtractor.splitShow("日谈公园"))
    }

    @Test
    fun `links say where the audio is`() {
        assertEquals(
            ChatLink.PodLinkEpisode("1553424581", "fac7363035378121645ea6e9d6b503ca"),
            ChatLink.parse("https://pod.link/1553424581/episode/fac7363035378121645ea6e9d6b503ca?utm_source=chatgpt.com"),
        )
        assertEquals(
            ChatLink.XiaoyuzhouShow("6139c18876177ac02fdcd804"),
            ChatLink.parse("https://www.xiaoyuzhoufm.com/podcast/6139c18876177ac02fdcd804?s=x"),
        )
        assertEquals(ChatLink.Other, ChatLink.parse("https://www.xiaoyuzhoufm.com/collection/episode/65a9fca8a578f8e81682d848"))
        assertEquals(ChatLink.Other, ChatLink.parse("https://www.twreporter.org/a/podcast-2023-12-20"))
        assertNotNull(ChatSharePage.shareUrlIn("Check this out https://chatgpt.com/share/6ae302ff-0430-83e8-8e2b-23788356e1af?ogimg=plain"))
    }

    @Test
    fun `a 小宇宙 episode page gives the title, date, audio and show`() {
        val e = XiaoyuzhouApi.parseEpisodePage(resource("xiaoyuzhou-episode.html"))
        assertEquals("E468.我是来自河南的犹太人", e.title)
        assertEquals("2021-02-19T11:22:33Z", java.time.Instant.ofEpochMilli(e.pubDateMs).toString())
        assertEquals(2_088_000L, e.durationMs)
        assertEquals("https://media.xyzcdn.net/lt4fZFfs-BkP6eS09OC6vKOy4H4v.m4a", e.audioUrl)
        assertEquals("故事FM", e.show.title)
        assertEquals("寇爱哲", e.show.author)
    }

    @Test
    fun `a 小宇宙 show page gives the show's name and newest episodes`() {
        val s = XiaoyuzhouApi.parseShowPage(resource("xiaoyuzhou-show.html"))
        assertEquals("噪音开始了", s.title)
        assertEquals(2, s.episodes.size)
        assertTrue(s.episodes.all { it.audioUrl != null })
    }

    @Test
    fun `Apple's per-show lookup carries each episode's GUID and audio`() {
        val episodes = ItunesApi.parseEpisodes(resource("apple-lookup-episodes.json"))
        val e = episodes.first { it.trackId == "1000523583667" }
        assertEquals("逃亡的人", e.title)
        assertEquals("2c766ca5-9f70-43d1-a00e-72f24d8dd94a".length, e.guid!!.length)
        assertNotNull(e.audioUrl)
    }

    @Test
    fun `a listening link after a bold title, and an item's indented lines, belong to it`() {
        val text = """
            1. **E468.我是来自河南的犹太人** [Listen](https://www.xiaoyuzhoufm.com/episode/602fa117139c2fe28f504d45)
            2. **說故事的人：逃亡的人**
               Two people recount leaving mainland China.
               [Apple Podcasts](https://podcasts.apple.com/tw/podcast/x/id1553424581?i=1000523583667)
            3. **日谈公园**
        """.trimIndent()
        val found = ChatPickExtractor.extract(ChatSharePage.Message(text, emptyMap()))
        assertEquals(3, found.size)
        assertEquals(ChatLink.XiaoyuzhouEpisode("602fa117139c2fe28f504d45"), ChatLink.parse(found[0].url!!))
        assertFalse(found[0].isShow)
        assertEquals(ChatLink.AppleEpisode("1553424581", "1000523583667"), ChatLink.parse(found[1].url!!))
        assertTrue(found[1].reason!!.startsWith("Two people recount leaving mainland China."))
        assertTrue(found[2].isShow)
    }

    @Test
    fun `plain numbered names count, plain sentences don't`() {
        val text = """
            1. 日谈公园
            2. 故事FM：逃亡的人 — a lighter one
            3. Start with whichever of these sounds most like your week.
        """.trimIndent()
        val found = ChatPickExtractor.extract(ChatSharePage.Message(text, emptyMap()))
        assertEquals(listOf("日谈公园", "故事FM：逃亡的人"), found.map { it.title })
        assertTrue(found[0].isShow)
        assertEquals("故事FM", found[1].showName)
        assertEquals("a lighter one", found[1].reason)
    }
}
