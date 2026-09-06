package com.podly.radio

import com.podly.data.db.RadioCandidateRow
import kotlin.math.exp
import kotlin.random.Random

/** A candidate plus where it came from, which decides how it is scored and blended. */
data class RadioCandidate(val row: RadioCandidateRow, val discovery: Boolean) {
    val episodeId: String get() = row.episodeId
    val podcastId: String get() = row.podcastId
}

/**
 * Chooses what radio plays next.
 *
 * Pure Kotlin with an injected [Random] so orderings are exactly assertable in tests.
 */
object RadioScorer {

    fun isEligible(
        candidate: RadioCandidate,
        profile: RadioProfile,
        exclude: Set<String>,
    ): Boolean {
        val row = candidate.row
        if (row.episodeId in exclude) return false
        val duration = row.durationMs
        if (duration != null) {
            profile.maxDurationMs?.let { if (duration > it) return false }
            profile.minDurationMs?.let { if (duration < it) return false }
        }
        // Language filters only bite when the candidate declares one; backlog rows
        // have no language until feeds are parsed for it.
        val language = row.language
        if (language != null && profile.languages.isNotEmpty()) {
            if (profile.languages.none { language.startsWith(it) }) return false
        }
        return true
    }

    fun score(
        candidate: RadioCandidate,
        profile: RadioProfile,
        nowMs: Long,
        jitter: Double,
    ): Double {
        val row = candidate.row
        val w = profile.weights
        var score = w.priority * row.priority

        row.userRating?.let { score += w.rating * ((it - 3).coerceIn(-2, 2) / 2.0) }

        val ageDays = ((nowMs - row.pubDateMs).coerceAtLeast(0)) / 86_400_000.0
        score += w.freshness * exp(-ageDays / 45.0)

        // Nudge up something already started but not nearly finished.
        val duration = row.durationMs ?: 0
        if (duration > 0 && row.playbackPositionMs > 0) {
            val progress = row.playbackPositionMs.toDouble() / duration
            if (progress in 0.05..0.85) score += w.resume
        }

        score += w.jitter * jitter

        if (row.lastServedAt > 0) {
            val servedDays = ((nowMs - row.lastServedAt).coerceAtLeast(0)) / 86_400_000.0
            score -= w.servedRecently * exp(-servedDays / 3.0)
        }
        score -= w.skipped * RadioCooldown.softPenalty(row.skipCount, row.lastSkippedAt, nowMs)
        return score
    }

    /**
     * Picks the next [count] episode ids, best first.
     *
     * The bucket is chosen before the candidate: a generator-supplied priority and a
     * locally computed freshness score are not on the same scale, so merging both
     * lists into one ranking would make the discovery/backlog blend an accident of
     * weight tuning instead of a property of the profile.
     */
    fun nextBatch(
        backlog: List<RadioCandidate>,
        discovery: List<RadioCandidate>,
        profile: RadioProfile,
        nowMs: Long,
        exclude: Set<String>,
        count: Int,
        random: Random,
    ): List<RadioCandidate> {
        fun rank(list: List<RadioCandidate>) = list
            .filter { isEligible(it, profile, exclude) }
            .map { it to score(it, profile, nowMs, random.nextDouble()) }
            .sortedByDescending { it.second }
            .map { it.first }
            .toMutableList()

        val backlogRanked = rank(backlog)
        val discoveryRanked = rank(discovery)
        val chosen = mutableListOf<RadioCandidate>()
        val usedEpisodes = exclude.toMutableSet()
        val usedShows = mutableSetOf<String>()

        repeat(count) {
            val preferDiscovery = random.nextDouble() < profile.discoveryShare
            val order = if (preferDiscovery) {
                listOf(discoveryRanked, backlogRanked)
            } else {
                listOf(backlogRanked, discoveryRanked)
            }
            val pick = order.firstNotNullOfOrNull { bucket ->
                take(bucket, usedEpisodes, usedShows, allowRepeatShow = false)
            } ?: order.firstNotNullOfOrNull { bucket ->
                // Only relax the one-per-show rule when it would otherwise stall.
                take(bucket, usedEpisodes, usedShows, allowRepeatShow = true)
            }
            if (pick != null) {
                chosen += pick
                usedEpisodes += pick.episodeId
                usedShows += pick.podcastId
            }
        }
        return chosen
    }

    private fun take(
        bucket: MutableList<RadioCandidate>,
        usedEpisodes: Set<String>,
        usedShows: Set<String>,
        allowRepeatShow: Boolean,
    ): RadioCandidate? {
        val index = bucket.indexOfFirst {
            it.episodeId !in usedEpisodes && (allowRepeatShow || it.podcastId !in usedShows)
        }
        if (index < 0) return null
        return bucket.removeAt(index)
    }
}
