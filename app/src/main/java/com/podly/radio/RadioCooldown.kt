package com.podly.radio

import kotlin.math.exp
import kotlin.math.min

/**
 * How long a skipped episode stays out of the way.
 *
 * A skip means "not now", never "never", so this is two layers: [blockedUntil] is a
 * materialised timestamp cheap enough to filter on in SQL, and [softPenalty] is a
 * decaying tie-break that stops a twice-skipped episode reappearing at the top of the
 * list the hour its block expires.
 *
 * Because `blockedUntil` is stored, changing this curve does not retroactively move
 * existing blocks — only newly recorded skips use the new numbers.
 */
object RadioCooldown {

    /** Escalating, and capped: even a much-skipped episode comes back eventually. */
    private val STEPS_MS = longArrayOf(
        6L * 60 * 60 * 1000,        // 6 hours
        2L * 24 * 60 * 60 * 1000,   // 2 days
        7L * 24 * 60 * 60 * 1000,   // 1 week
        30L * 24 * 60 * 60 * 1000,  // 30 days
        90L * 24 * 60 * 60 * 1000,  // 90 days
    )

    /** Skips older than this stop counting, so they cannot compound forever. */
    private const val DECAY_MS = 180L * 24 * 60 * 60 * 1000

    fun blockedUntil(skipCount: Int, skippedAtMs: Long): Long {
        if (skipCount <= 0) return 0
        val step = STEPS_MS[min(skipCount, STEPS_MS.size) - 1]
        return skippedAtMs + step
    }

    /** The skip count still in force, ignoring skips old enough to have decayed. */
    fun effectiveSkipCount(storedCount: Int, lastSkippedAtMs: Long, nowMs: Long): Int =
        if (lastSkippedAtMs <= 0 || nowMs - lastSkippedAtMs > DECAY_MS) 0 else storedCount

    /**
     * 0..1 penalty applied after the hard block lifts, decaying over roughly a week
     * per accumulated skip.
     */
    fun softPenalty(skipCount: Int, lastSkippedAtMs: Long, nowMs: Long): Double {
        val skips = effectiveSkipCount(skipCount, lastSkippedAtMs, nowMs)
        if (skips <= 0) return 0.0
        val days = (nowMs - lastSkippedAtMs).coerceAtLeast(0) / 86_400_000.0
        val halfLife = 7.0 * skips
        return min(1.0, skips / 3.0) * exp(-days / halfLife)
    }
}
