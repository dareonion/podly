package com.podly.playback

import com.podly.data.db.EpisodeProgress

/** The most a queue played on through a show holds: the Cast window, so none drop off when casting. */
internal const val SHOW_CONTINUATION_LIMIT = MAX_CAST_QUEUE_ITEMS

/**
 * The queue for [episodeId] played from inside its show: that episode, then the show's
 * later unfinished episodes in the show's own order ([order]), [limit] in all.
 * Down the list as it is shown, so a serial set to oldest first plays forward and a
 * newest-first show goes back through its back catalogue. Finished episodes are left
 * out: in the car there is no easy way to skip one that comes round again.
 */
internal fun continueThroughShow(
    order: List<EpisodeProgress>,
    episodeId: String,
    limit: Int = SHOW_CONTINUATION_LIMIT,
): List<String> {
    val at = order.indexOfFirst { it.id == episodeId }
    if (at < 0) return listOf(episodeId)
    val later = order.subList(at + 1, order.size).asSequence()
        .filterNot { it.completed }
        .map { it.id }
        .take(limit - 1)
    return listOf(episodeId) + later
}
