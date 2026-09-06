package com.podly.playback

/**
 * How deep the radio queue is kept.
 *
 * Shallow on purpose. Every queued item is a commitment made *before* the user's next
 * skip is known, so a long queue keeps playing picks chosen against stale feedback.
 * Trimming what is already played also bounds the whole queue at about ten items,
 * which is what keeps the saved queue snapshot small and keeps a refilling radio
 * session far below [MAX_CAST_QUEUE_ITEMS] — [castQueueWindow] only applies when the
 * queue is set wholesale, so it would never protect an append.
 */
internal const val RADIO_LOOKAHEAD = 4
internal const val RADIO_MIN_LOOKAHEAD = 2
internal const val RADIO_LOOKBACK = 5

internal data class RadioQueuePlan(
    /** How many fresh candidates to request; 0 when the queue is deep enough. */
    val fetchCount: Int,
    /** Leading items to drop, or null. Never contains or exceeds the current index. */
    val trimRange: IntRange?,
)

internal fun radioQueuePlan(
    itemCount: Int,
    currentIndex: Int,
    lookahead: Int = RADIO_LOOKAHEAD,
    minLookahead: Int = RADIO_MIN_LOOKAHEAD,
    lookback: Int = RADIO_LOOKBACK,
): RadioQueuePlan {
    if (itemCount <= 0) return RadioQueuePlan(fetchCount = lookahead, trimRange = null)
    val index = currentIndex.coerceIn(0, itemCount - 1)
    val ahead = itemCount - 1 - index
    val fetch = if (ahead <= minLookahead) (lookahead - ahead).coerceAtLeast(0) else 0
    val behind = index
    val trim = if (behind > lookback) 0..(behind - lookback - 1) else null
    return RadioQueuePlan(fetchCount = fetch, trimRange = trim)
}
