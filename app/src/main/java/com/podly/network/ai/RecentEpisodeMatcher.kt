package com.podly.network.ai

import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Matches an AI-recommended recent episode to a real feed episode.
 *
 * The recommender's [com.podly.network.ai.AiRecentEpisodePick.episodeTitle] is
 * usually a paraphrase, not the verbatim RSS title (e.g. it returns "Community
 * College as a Career-Change Hack" for the feed's "Community colleges are kind of
 * underrated"). Exact/substring matching therefore misses most picks, so we score
 * candidates on token overlap plus proximity to the recommended publish date — the
 * podcast and the approximate date come back far more reliably than the exact title.
 *
 * Pure Kotlin (no Android deps) so it is covered by JVM unit tests.
 */
object RecentEpisodeMatcher {

    data class Candidate(val title: String, val description: String?, val pubDateMs: Long)

    /**
     * Index of the best feed match for the pick, or null if nothing scores high enough.
     *
     * [showTitle], when known, is the feed's own name: picks and feed titles often
     * both carry it ("說故事的人：…"), and words every title shares say nothing about
     * which episode is meant, so they don't count toward a match.
     */
    fun bestMatch(
        title: String,
        publishedApprox: String?,
        candidates: List<Candidate>,
        showTitle: String? = null,
    ): Int? {
        if (candidates.isEmpty()) return null
        val wantedNorm = normalize(title)
        // An empty normal form (a title of symbols only) would "equal" every other
        // one; it once matched any all-Chinese pick to the feed's first all-Chinese
        // episode, back when only ASCII survived normalizing.
        if (wantedNorm.isNotEmpty()) {
            candidates.indexOfFirst { normalize(it.title) == wantedNorm }
                .let { if (it >= 0) return it }
        }

        val boilerplate = showTitle?.let(::tokens).orEmpty() + sharedTokens(candidates)
        val wantedTokens = tokens(title) - boilerplate
        if (wantedTokens.isEmpty()) return null
        val wantedNumbers = episodeNumbers(title)
        val wantedParts = parts(title)
        val chineseTitle = wantedTokens.count { isCjk(it) } * 2 >= wantedTokens.size
        // The title without the show's name in front, for finding it inside show notes.
        val wantedCore = showTitle?.let(::normalize)?.takeIf { it.isNotEmpty() && wantedNorm.startsWith(it) }
            ?.let { wantedNorm.removePrefix(it).trim() } ?: wantedNorm
        val approx = parseApprox(publishedApprox)

        var bestIdx = -1
        var bestScore = 0.0
        candidates.forEachIndexed { i, c ->
            // Different episode numbers mean a different episode: "白色恐怖 2" is not
            // "白色恐怖 1", however much else they share. The same number, with any word
            // in common, is a match however loosely the rest is paraphrased ("Ep268
            // 60個美國同學遊台灣" for "Ep.268 從美國外帶60個同學回台灣！").
            val candNumbers = episodeNumbers(c.title)
            val candParts = parts(c.title)
            // Episode number and part are checked apart: a shared "EP12" must not
            // hide "Part 2" against "Part 1".
            if (conflict(wantedNumbers, candNumbers) || conflict(wantedParts, candParts)) return@forEachIndexed
            val sameNumber = wantedNumbers.any { it in candNumbers }
            val candNorm = normalize(c.title)
            val substring = wantedNorm.isNotEmpty() && candNorm.isNotEmpty() &&
                (candNorm.contains(wantedNorm) || wantedNorm.contains(candNorm))
            // The model sometimes names a segment described in the show notes rather
            // than the episode itself, so also match against the description — at a
            // discount, since notes are long and noisier than the title. For Chinese,
            // where any long text shares character pairs with any title, only notes
            // containing the whole title count.
            val descScore = when {
                c.description.isNullOrBlank() -> 0.0
                chineseTitle -> if (wantedCore.codePointCount(0, wantedCore.length) >= 4 &&
                    normalize(c.description).contains(wantedCore)
                ) {
                    CJK_MIN_TITLE_SCORE
                } else {
                    0.0
                }
                else -> overlap(wantedTokens, tokens(c.description) - boilerplate) * DESC_WEIGHT
            }
            val titleOverlap = overlap(wantedTokens, tokens(c.title) - boilerplate)
            val rawTitleScore = maxOf(
                if (substring) SUBSTRING_SCORE else 0.0,
                titleOverlap,
                descScore,
                if (sameNumber && titleOverlap > 0) SAME_NUMBER_SCORE else 0.0,
            )
            // Episodes of one Chinese series share a prefix (白色恐怖：誰是受害者？ /
            // 白色恐怖：誰的正義？ share half their character pairs), and chat picks copy
            // Chinese titles rather than paraphrasing them, so ask for most of the title.
            val titleScore = if (chineseTitle && rawTitleScore < CJK_MIN_TITLE_SCORE) 0.0 else rawTitleScore
            // Date proximity boosts a plausible title but can never carry a match alone:
            // a short-window weekly show publishes every episode "near" the pick's date,
            // so date-only scoring swaps in whatever unrelated episode ran that week.
            val score = if (titleScore > 0) {
                titleScore + (approx?.let { dateScore(it, c.pubDateMs) } ?: 0.0)
            } else {
                0.0
            }
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        return bestIdx.takeIf { it >= 0 && bestScore >= ACCEPT_THRESHOLD }
    }

    /**
     * Tokens in most of a feed's titles: its name, "EP", a recurring series tag.
     * Only for feeds big enough for "most" to mean something.
     */
    private fun sharedTokens(candidates: List<Candidate>): Set<String> {
        if (candidates.size < SHARED_MIN_FEED) return emptySet()
        val counts = HashMap<String, Int>()
        candidates.forEach { c -> tokens(c.title).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts.filterValues { it * 10 > candidates.size * 6 }.keys
    }

    /**
     * Episode and part markers, numbers without leading zeros ("Vol. 05" and "vol.5"
     * agree): after EP / E / Vol / No / Part / # / 第, at the very start ("282 基辅…",
     * "15.奥斯卡…"), alone at the end before any bracketed aside ("…柏林墙与史塔西 2",
     * "白色恐怖 2 (2021)"), and 上 / 中 / 下 parts ("療傷之路（下）", "上集"). Not
     * numbers in the text or in a bracketed date: "E913" against "…的五年：19 岁女孩…",
     * or "(June 15, 2026)" against "EP125", is no conflict.
     */
    internal fun episodeNumbers(s: String): Set<String> =
        EPISODE_NUMBER.findAll(Normalizer.normalize(s, Normalizer.Form.NFKC))
            .mapNotNull { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }
            .map { it.trimStart('0').ifEmpty { "0" } }
            .toSet()

    /** Part markers: "Part 2", "Pt. 2", "（下）", "上集". Kept apart from episode numbers. */
    internal fun parts(s: String): Set<String> =
        PART.findAll(Normalizer.normalize(s, Normalizer.Form.NFKC))
            .map { m -> m.groupValues.drop(1).first { it.isNotEmpty() }.trimStart('0').ifEmpty { "0" } }
            .toSet()

    private fun conflict(a: Set<String>, b: Set<String>) = a.isNotEmpty() && b.isNotEmpty() && a.none { it in b }

    // A trailing number may sit before one bracketed aside: (2021), [2021], 【2021】.
    private val EPISODE_NUMBER = Regex(
        """(?i)(?:(?<![a-z])(?:ep|e|vol|no)\.?\s?|#\s?|第\s?)(\d+)(?!\s*(?:集|篇|部))""" +
            """|^\s*(\d+)(?=[\s.、：:]|$)""" +
            """|(?<=\s)(?<!(?:part|pt|pt\.)\s)(\d+)\s*(?:[(\[【][^)\]】]*[)\]】]\s*)?$""",
    )
    private val PART = Regex("""(?i)(?<![a-z])(?:part|pt)\.?\s?(\d+)|\(\s*([上中下])\s*\)|([上中下])(?:集|篇|部)""")

    private const val ACCEPT_THRESHOLD = 0.45
    private const val SUBSTRING_SCORE = 0.6
    private const val DESC_WEIGHT = 0.6
    private const val CJK_MIN_TITLE_SCORE = 0.7
    private const val SHARED_MIN_FEED = 4
    private const val SAME_NUMBER_SCORE = 0.75

    private data class Approx(val epochDay: Long, val hasDay: Boolean)

    /** Same-day publication is a strong signal for daily shows; months are a weak nudge. */
    private fun dateScore(a: Approx, pubDateMs: Long): Double {
        val days = abs(pubDateMs / MILLIS_PER_DAY - a.epochDay)
        return if (a.hasDay) {
            when {
                days <= 1 -> 0.5
                days <= 3 -> 0.3
                days <= 7 -> 0.15
                else -> 0.0
            }
        } else {
            if (days <= 20) 0.15 else 0.0
        }
    }

    private val DAY_FORMATS = listOf("yyyy-MM-dd", "MMMM d, yyyy", "MMM d, yyyy", "MMMM d yyyy")
    private val MONTH_FORMATS = listOf("yyyy-MM", "MMMM yyyy", "MMM yyyy")

    private fun parseApprox(raw: String?): Approx? {
        val s = raw?.trim()?.ifEmpty { null } ?: return null
        for (f in DAY_FORMATS) {
            runCatching { LocalDate.parse(s, DateTimeFormatter.ofPattern(f, Locale.ENGLISH)) }
                .getOrNull()?.let { return Approx(it.toEpochDay(), hasDay = true) }
        }
        for (f in MONTH_FORMATS) {
            runCatching { YearMonth.parse(s, DateTimeFormatter.ofPattern(f, Locale.ENGLISH)) }
                .getOrNull()?.let { return Approx(it.atDay(15).toEpochDay(), hasDay = false) }
        }
        return null
    }

    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are",
        "was", "were", "why", "how", "what", "whats", "it", "its", "that", "this", "as",
        "at", "from", "by", "be", "does", "do", "will", "can", "about",
    )

    /**
     * Letters and digits of any script, lowercased, everything else a single space.
     * NFKC first, so full-width forms (Ｅ４６８, （…）) read as their ASCII twins.
     */
    internal fun normalize(s: String): String =
        // Brackets are separators like any punctuation, their contents kept: deleting
        // "(...)" once made 療傷之路（上） and 療傷之路（下） the same title.
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /**
     * Words for alphabetic scripts; for Chinese, Japanese and Korean, which don't put
     * spaces between words, every pair of adjacent characters (a lone character
     * counts on its own). Two different Chinese titles then share few tokens, where
     * the whole run used to be one token or, before Unicode normalizing, nothing.
     */
    internal fun tokens(s: String): Set<String> {
        val out = mutableSetOf<String>()
        for (word in normalize(s).split(' ')) {
            val cjk = mutableListOf<String>()
            val rest = StringBuilder()
            fun flushCjk() {
                if (cjk.size == 1) out += cjk[0] else cjk.zipWithNext { a, b -> out += a + b }
                cjk.clear()
            }
            word.codePoints().forEach { cp ->
                if (Character.UnicodeScript.of(cp) in CJK_SCRIPTS) {
                    cjk += String(Character.toChars(cp))
                    rest.append(' ')
                } else {
                    flushCjk()
                    rest.appendCodePoint(cp)
                }
            }
            flushCjk()
            for (token in rest.split(' ')) {
                val stemmed = token.removeSuffix("s") // crude stemming: college/colleges, want/wants
                if (stemmed.length >= 3 && stemmed !in STOPWORDS) out += stemmed
            }
        }
        return out
    }

    private fun isCjk(token: String) = Character.UnicodeScript.of(token.codePointAt(0)) in CJK_SCRIPTS

    private val CJK_SCRIPTS = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
    )

    /** Shared tokens over the smaller set, so a short title that is a subset still scores high. */
    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / minOf(a.size, b.size)
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
