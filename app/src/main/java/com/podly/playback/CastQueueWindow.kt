package com.podly.playback

/** Well under Cast's 512 KB per-message ceiling even with long titles and artwork URLs. */
internal const val MAX_CAST_QUEUE_ITEMS = 50

/** Items kept before the current one so "previous" still works while casting. */
internal const val CAST_QUEUE_LOOKBACK = 5

/**
 * Trims a queue to what a Cast device can actually be sent, returning the
 * window and the current item's index within it.
 *
 * `RemoteMediaClient.load` serialises the whole queue into a single Cast
 * protocol message capped at 512 KB. A full library queue overflows that, and
 * the SDK throws `IllegalArgumentException: Message exceeds maximum size`,
 * which crashes the app on every press of play while connected to a receiver.
 * Local playback is unaffected and keeps the whole queue.
 */
internal fun <T> castQueueWindow(
    items: List<T>,
    index: Int,
    limit: Int = MAX_CAST_QUEUE_ITEMS,
    lookback: Int = CAST_QUEUE_LOOKBACK,
): Pair<List<T>, Int> {
    if (items.size <= limit) return items to index
    val safeIndex = index.coerceIn(0, items.lastIndex)
    val start = (safeIndex - lookback).coerceIn(0, items.size - limit)
    return items.subList(start, start + limit) to (safeIndex - start)
}
