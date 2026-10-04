package com.podly.playback

/** The player operation that puts an episode where "Play next" / "Add to queue" asked. */
internal sealed interface QueuePlacement {
    data class Insert(val index: Int) : QueuePlacement

    /** Already queued elsewhere: moved rather than added, so it never plays twice. */
    data class Move(val from: Int, val to: Int) : QueuePlacement

    /** It is the episode playing now; queueing it again would mean nothing. */
    data object AlreadyPlaying : QueuePlacement

    /** "Add to queue" for an episode that is already coming up; left where it is. */
    data object AlreadyQueued : QueuePlacement
}

/**
 * Where [episodeId] goes in a queue of [queuedIds] playing [currentIndex]: straight
 * after the current episode when [playNext], otherwise at the end. `to` in a
 * [QueuePlacement.Move] is the index after the move, as `Player.moveMediaItem` wants.
 */
internal fun queuePlacement(
    queuedIds: List<String>,
    currentIndex: Int,
    episodeId: String,
    playNext: Boolean,
): QueuePlacement {
    if (queuedIds.isEmpty()) return QueuePlacement.Insert(0)
    val current = currentIndex.coerceIn(0, queuedIds.lastIndex)
    if (queuedIds[current] == episodeId) return QueuePlacement.AlreadyPlaying
    // Prefer an upcoming copy over one already played, which is the one that matters.
    val existing = queuedIds.withIndex()
        .filter { it.value == episodeId }
        .map { it.index }
        .let { found -> found.firstOrNull { it > current } ?: found.firstOrNull() }
        ?: return QueuePlacement.Insert(if (playNext) current + 1 else queuedIds.size)
    return when {
        // Moving an earlier item out shifts the current one down a place.
        playNext -> QueuePlacement.Move(existing, if (existing < current) current else current + 1)
        existing > current -> QueuePlacement.AlreadyQueued
        else -> QueuePlacement.Move(existing, queuedIds.lastIndex)
    }
}
