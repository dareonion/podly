package com.podly

import com.podly.network.ai.RecentEpisodeMatcher
import com.podly.network.ai.RecentEpisodeMatcher.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Cases drawn from real cached recommendations vs. the actual feeds, where the AI's
 * episode title is a paraphrase rather than the verbatim RSS title.
 */
class RecentEpisodeMatcherTest {

    private fun dayMs(iso: String): Long =
        LocalDate.parse(iso).toEpochDay() * 86_400_000L

    @Test
    fun `exact title matches`() {
        val candidates = listOf(
            Candidate("Some Other Episode", null, dayMs("2026-06-01")),
            Candidate("Interview With \"Game Changer\" Host & Dropout CEO Sam Reich", null, dayMs("2026-06-22")),
        )
        val idx = RecentEpisodeMatcher.bestMatch(
            "Interview With \"Game Changer\" Host & Dropout CEO Sam Reich",
            "June 22, 2026",
            candidates,
        )
        assertEquals(1, idx)
    }

    @Test
    fun `paraphrased title matches on token overlap`() {
        // AI: "Community College as a Career-Change Hack" -> feed: "Community colleges are kind of underrated"
        val candidates = listOf(
            Candidate("The SpaceX IPO drama explained", null, dayMs("2026-06-20")),
            Candidate("Community colleges are kind of underrated", null, dayMs("2026-06-15")),
            Candidate("Should we tax AI?", null, dayMs("2026-06-10")),
        )
        val idx = RecentEpisodeMatcher.bestMatch(
            "Community College as a Career-Change Hack (June 15, 2026)",
            "2026-06-15",
            candidates,
        )
        assertEquals(1, idx)
    }

    @Test
    fun `weak title overlap still matches via same-day publish date`() {
        // AI: "Trump and the Save America Act Voter ID Push" -> feed: "What's Trump's beef with Senate Republicans?"
        val candidates = listOf(
            Candidate("Iran \"deal\": winners, losers, and regional impact", null, dayMs("2026-06-23")),
            Candidate("What's Trump's beef with Senate Republicans?", null, dayMs("2026-06-22")),
            Candidate("These swing voters are sour on Trump", null, dayMs("2026-06-20")),
        )
        val idx = RecentEpisodeMatcher.bestMatch(
            "Trump and the Save America Act Voter ID Push (June 22, 2026)",
            "June 22, 2026",
            candidates,
        )
        assertEquals(1, idx)
    }

    @Test
    fun `segment named in the show notes matches via description`() {
        // AI named a segment: "Mary Moriarty on charging ICE agents and the new 'FAFO'
        // prosecutors' coalition" -> real episode "The Malicious Incompetence of Trump's DOJ"
        // whose show notes describe that interview.
        val candidates = listOf(
            Candidate("Affirmative Action for Mediocre Men", "A look at a recent ruling.", dayMs("2026-06-08")),
            Candidate(
                "The Malicious Incompetence of Trump's DOJ",
                "Leah speaks with Mary Moriarty, Hennepin County Attorney, about the charges " +
                    "her office filed against ICE agents and the new FAFO prosecutors' coalition.",
                dayMs("2026-06-15"),
            ),
            Candidate("Why is SCOTUS Hoarding Opinions?", "On the Court's slow opinion season.", dayMs("2026-06-22")),
        )
        val idx = RecentEpisodeMatcher.bestMatch(
            "Mary Moriarty on charging ICE agents and the new 'FAFO' prosecutors' coalition",
            "2026-06",
            candidates,
        )
        assertEquals(1, idx)
    }

    @Test
    fun `same-day date alone with no title overlap is rejected`() {
        // Real regression: the Robby Hoffman pick had rolled off The Interview's feed,
        // and same-day date proximity alone (0.5 > threshold) matched the unrelated
        // Mick Jagger episode that was still in the feed.
        val candidates = listOf(
            Candidate("Mick Jagger Can't Stop Moving", null, dayMs("2026-07-18")),
            Candidate("A Conversation About the Economy", null, dayMs("2026-07-11")),
        )
        val idx = RecentEpisodeMatcher.bestMatch(
            "Robby Hoffman Is Trying Her Best",
            "2026-07-18",
            candidates,
        )
        assertNull(idx)
    }

    @Test
    fun `unrelated episodes far from the date are rejected`() {
        val candidates = listOf(
            Candidate("A totally different show topic about gardening", null, dayMs("2026-01-01")),
            Candidate("Another unrelated cooking episode", null, dayMs("2026-02-01")),
        )
        val idx = RecentEpisodeMatcher.bestMatch(
            "Does Forward Guidance Help or Hurt the Economy?",
            "2026-06-18",
            candidates,
        )
        assertNull(idx)
    }

    @Test
    fun `empty candidate list returns null`() {
        assertNull(RecentEpisodeMatcher.bestMatch("Anything", "2026-06-18", emptyList()))
    }

    // Titles below are 說故事的人's real feed titles (anchor.fm/s/4bbcf5d4, read 2026-10-29)
    // and the ones a ChatGPT recommendations chat gave for them.
    private val storyteller = listOf(
        Candidate("一生只做一件事", null, dayMs("2023-12-10")),
        Candidate("我走的時候，不要哭", null, dayMs("2023-11-26")),
        Candidate("白色恐怖：療傷之路", null, dayMs("2022-06-28")),
        Candidate("白色恐怖：誰是受害者？", null, dayMs("2021-06-21")),
        Candidate("白色恐怖：誰的正義？", null, dayMs("2021-06-13")),
        Candidate("逃亡的人", null, dayMs("2021-05-30")),
    )

    @Test
    fun `an all-Chinese pick is not matched to the first all-Chinese episode`() {
        // Before Unicode normalizing both sides were "", so this returned 0.
        assertEquals(1, RecentEpisodeMatcher.bestMatch("我走的時候，不要哭", null, storyteller))
    }

    @Test
    fun `a pick prefixed with its show name finds the episode`() {
        assertEquals(1, RecentEpisodeMatcher.bestMatch("說故事的人：我走的時候，不要哭", null, storyteller))
        assertEquals(3, RecentEpisodeMatcher.bestMatch("說故事的人：白色恐怖，誰是受害者？", null, storyteller))
        assertEquals(4, RecentEpisodeMatcher.bestMatch("說故事的人：白色恐怖，誰的正義？", null, storyteller))
    }

    @Test
    fun `a sibling sharing the series prefix is not accepted for a missing episode`() {
        val withoutIt = storyteller.filterNot { it.title == "白色恐怖：誰是受害者？" }
        assertNull(RecentEpisodeMatcher.bestMatch("白色恐怖，誰是受害者？", null, withoutIt))
    }

    @Test
    fun `an unrelated Chinese title matches nothing`() {
        assertNull(RecentEpisodeMatcher.bestMatch("在一个被监视的家庭长大", null, storyteller))
    }

    @Test
    fun `episode numbers and full-width punctuation still match`() {
        val gushi = listOf(
            Candidate("E467.我在纽约开出租", null, dayMs("2021-02-17")),
            Candidate("E468.我是来自河南的犹太人", null, dayMs("2021-02-19")),
        )
        assertEquals(1, RecentEpisodeMatcher.bestMatch("E468 我是来自河南的犹太人", null, gushi))
        assertEquals(1, RecentEpisodeMatcher.bestMatch("Ｅ４６８（2021）我是来自河南的犹太人", null, gushi))
    }

    @Test
    fun `a title of symbols only matches nothing`() {
        assertNull(RecentEpisodeMatcher.bestMatch("☀️ —", null, storyteller))
    }

    @Test
    fun `installments in brackets stay distinct`() {
        val parts = listOf(
            Candidate("白色恐怖：療傷之路（上）", null, dayMs("2022-06-21")),
            Candidate("白色恐怖：療傷之路（下）", null, dayMs("2022-06-28")),
        )
        assertEquals(1, RecentEpisodeMatcher.bestMatch("白色恐怖：療傷之路（下）", null, parts))
        assertEquals(0, RecentEpisodeMatcher.bestMatch("白色恐怖：療傷之路(上)", null, parts))
    }

    @Test
    fun `a different installment number is not accepted for a missing one`() {
        val one = listOf(Candidate("我改装凯迪拉克帮人逃出东德 | 柏林墙与史塔西 1", null, dayMs("2026-08-13")))
        assertNull(RecentEpisodeMatcher.bestMatch("在一个被监视的家庭长大｜柏林墙与史塔西 2", null, one))
        assertNull(RecentEpisodeMatcher.bestMatch("白色恐怖 2", null, listOf(Candidate("白色恐怖 1", null, 0))))
    }

    @Test
    fun `the show's own name does not carry a sibling over the line`() {
        val prefixed = listOf(Candidate("說故事的人：白色恐怖：誰的正義？", null, dayMs("2021-06-13")))
        assertNull(
            RecentEpisodeMatcher.bestMatch("說故事的人：白色恐怖，誰是受害者？", null, prefixed, showTitle = "說故事的人"),
        )
        assertEquals(
            0,
            RecentEpisodeMatcher.bestMatch("說故事的人：白色恐怖，誰的正義", null, prefixed, showTitle = "說故事的人"),
        )
    }

    @Test
    fun `words in most of a feed's titles don't count`() {
        // No showTitle given: the shared "說故事的人" prefix is found from the feed itself.
        val feed = storyteller.map { it.copy(title = "說故事的人：" + it.title) }
            .filterNot { it.title.endsWith("誰是受害者？") }
        assertNull(RecentEpisodeMatcher.bestMatch("說故事的人：白色恐怖，誰是受害者？", null, feed))
        assertEquals(0, RecentEpisodeMatcher.bestMatch("說故事的人：一生只做一件事", null, feed))
    }

    @Test
    fun `a Chinese pick can match a segment named in the show notes`() {
        val feed = listOf(
            Candidate("EP12 本週精選", "本集內容：一、我走的時候，不要哭；二、聽眾來信", dayMs("2023-11-26")),
            Candidate("EP13 本週精選", "本集內容：一生只做一件事", dayMs("2023-12-10")),
        )
        assertEquals(0, RecentEpisodeMatcher.bestMatch("我走的時候，不要哭", null, feed))
        assertNull(RecentEpisodeMatcher.bestMatch("在一个被监视的家庭长大", null, feed))
    }

    @Test
    fun `an episode number is not contradicted by numbers in the text`() {
        // 故事FM's feed drops the "E913" the chat (and 小宇宙) give it.
        val feed = listOf(
            Candidate("E914.妈妈说去上个厕所，从此再也没有回来", null, dayMs("2026-10-28")),
            Candidate("一个人民陪审员的五年：19 岁女孩的诈骗案，让我第一次「顶撞」法官", null, dayMs("2026-10-21")),
        )
        assertEquals(1, RecentEpisodeMatcher.bestMatch("E913 一个人民陪审员的五年", null, feed))
    }

    @Test
    fun `the same episode number carries a loosely paraphrased title`() {
        val feed = listOf(
            Candidate("Ep.274 新手媽媽找回背包客初心的日本縱貫線！10 個月 + 3 歲的 75 天之旅", null, dayMs("2026-06-07")),
            Candidate("Ep.268 從美國外帶60個同學回台灣！外國人最愛台灣的點居然是….. ？ ft. 矽谷宅泡", null, dayMs("2026-05-12")),
        )
        assertEquals(1, RecentEpisodeMatcher.bestMatch("解鎖地球 — Ep268 60個美國同學遊台灣", null, feed, "解鎖地球：旅行、歷史、文化"))
        assertNull(RecentEpisodeMatcher.bestMatch("解鎖地球 — Ep270 完全不同的主題", null, feed, "解鎖地球：旅行、歷史、文化"))
    }

    @Test
    fun `a missing part is not replaced by the other part`() {
        val onlyFirst = listOf(Candidate("白色恐怖：療傷之路（上）", null, dayMs("2022-06-21")))
        assertNull(RecentEpisodeMatcher.bestMatch("白色恐怖：療傷之路（下）", null, onlyFirst))
        assertNull(RecentEpisodeMatcher.bestMatch("白色恐怖 2 (2021)", null, listOf(Candidate("白色恐怖 1 (2021)", null, 0))))
        assertNull(RecentEpisodeMatcher.bestMatch("Hard Choices, Part 2", null, listOf(Candidate("Hard Choices, Part 1", null, 0))))
    }

    @Test
    fun `a date in brackets is not an episode number`() {
        val feed = listOf(Candidate("EP125 Community colleges are kind of underrated", null, dayMs("2026-06-15")))
        assertEquals(0, RecentEpisodeMatcher.bestMatch("Community College as a Career-Change Hack (June 15, 2026)", "2026-06-15", feed))
    }

    @Test
    fun `an episode number shared does not hide a different part`() {
        assertNull(RecentEpisodeMatcher.bestMatch("EP12 Hard Choices Part 2", null, listOf(Candidate("EP12 Hard Choices Part 1", null, 0))))
        assertNull(RecentEpisodeMatcher.bestMatch("E12 白色恐怖：療傷之路（下）", null, listOf(Candidate("E12 白色恐怖：療傷之路（上）", null, 0))))
        assertNull(RecentEpisodeMatcher.bestMatch("EP13 Hard Choices Part 2", null, listOf(Candidate("EP12 Hard Choices Part 2", null, 0))))
        assertEquals(0, RecentEpisodeMatcher.bestMatch("EP12 Hard Choices, Part 2", null, listOf(Candidate("EP12 Hard Choices Part 2", null, 0))))
    }

    @Test
    fun `a trailing part number before square or Chinese brackets still counts`() {
        assertNull(RecentEpisodeMatcher.bestMatch("白色恐怖 2 [2021]", null, listOf(Candidate("白色恐怖 1 [2021]", null, 0))))
        assertNull(RecentEpisodeMatcher.bestMatch("白色恐怖 2 【2021】", null, listOf(Candidate("白色恐怖 1 【2021】", null, 0))))
    }

    @Test
    fun `a number before 集 is not cut short`() {
        // Backing off a digit to get past 集 once read 第12集 and 第15集 both as episode 1,
        // and that shared number carried an episode sharing only 故事 past the bar.
        assertEquals(emptySet<String>(), RecentEpisodeMatcher.episodeNumbers("第192集 台灣故事"))
        assertNull(RecentEpisodeMatcher.bestMatch("第12集 台灣故事", null, listOf(Candidate("第15集 日本料理的故事", null, 0))))
    }
}
