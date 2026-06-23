package com.podly.network.ai

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

    /** Index of the best feed match for the pick, or null if nothing scores high enough. */
    fun bestMatch(title: String, publishedApprox: String?, candidates: List<Candidate>): Int? {
        if (candidates.isEmpty()) return null
        val wantedNorm = normalize(title)
        candidates.indexOfFirst { normalize(it.title) == wantedNorm }
            .let { if (it >= 0) return it }

        val wantedTokens = tokens(title)
        val approx = parseApprox(publishedApprox)

        var bestIdx = -1
        var bestScore = 0.0
        candidates.forEachIndexed { i, c ->
            val candNorm = normalize(c.title)
            val substring = wantedNorm.isNotEmpty() &&
                (candNorm.contains(wantedNorm) || wantedNorm.contains(candNorm))
            // The model sometimes names a segment described in the show notes rather
            // than the episode itself, so also match against the description — at a
            // discount, since notes are long and noisier than the title.
            val descOverlap = if (!c.description.isNullOrBlank()) {
                overlap(wantedTokens, tokens(c.description)) * DESC_WEIGHT
            } else {
                0.0
            }
            val titleScore = maxOf(
                if (substring) SUBSTRING_SCORE else 0.0,
                overlap(wantedTokens, tokens(c.title)),
                descOverlap,
            )
            val score = titleScore + (approx?.let { dateScore(it, c.pubDateMs) } ?: 0.0)
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        return bestIdx.takeIf { it >= 0 && bestScore >= ACCEPT_THRESHOLD }
    }

    private const val ACCEPT_THRESHOLD = 0.45
    private const val SUBSTRING_SCORE = 0.6
    private const val DESC_WEIGHT = 0.6

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

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tokens(s: String): Set<String> =
        normalize(s).split(' ')
            .map { it.removeSuffix("s") } // crude stemming: college/colleges, want/wants
            .filter { it.length >= 3 && it !in STOPWORDS }
            .toSet()

    /** Shared tokens over the smaller set, so a short title that is a subset still scores high. */
    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / minOf(a.size, b.size)
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
