package com.podly.playback

/** Ids per query: under SQLite's bound-variable limit. */
internal const val EPISODE_LOOKUP_BATCH = 500

/**
 * Looks [ids] up [batch] at a time with [fetch] and returns the rows in the same
 * order, null where an id is null or has no row; a repeated id gets its row each
 * time. A queue from the Library list holds thousands of episodes, and looking
 * them up one query each kept playback from starting for about 30 seconds.
 */
internal suspend fun <T : Any> lookUpInOrder(
    ids: List<String?>,
    idOf: (T) -> String,
    batch: Int = EPISODE_LOOKUP_BATCH,
    fetch: suspend (List<String>) -> List<T>,
): List<T?> {
    val wanted = ids.filterNotNull().distinct()
    if (wanted.isEmpty()) return ids.map { null }
    val byId = HashMap<String, T>(wanted.size)
    for (chunk in wanted.chunked(batch)) {
        fetch(chunk).associateByTo(byId, idOf)
    }
    return ids.map { id -> id?.let(byId::get) }
}
