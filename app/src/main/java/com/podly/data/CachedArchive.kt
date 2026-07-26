package com.podly.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches a provider's real answers in memory so repeated rescues (re-imports,
 * save-as-playlist retries) don't re-issue identical requests. Both a found show
 * and a definitive "provider doesn't index this show" (empty list) are cached;
 * null "can't answer" results (no creds, network/auth errors, rate limits) never
 * are, so fixing creds or connectivity takes effect on the very next attempt.
 *
 * Pure Kotlin (no Android deps) so it is covered by JVM unit tests; the optional
 * [log] hook lets the app route hits to logcat.
 */
class CachedArchive(
    private val delegate: EpisodeArchive,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : EpisodeArchive {
    override val name get() = delegate.name

    private class Entry(val atMs: Long, val episodes: List<ArchiveEpisode>)

    // Access-ordered so eviction drops the least recently used feed.
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > maxEntries
    }
    private val lock = Mutex()

    override suspend fun episodesFor(feedUrl: String, title: String): List<ArchiveEpisode>? {
        lock.withLock {
            entries[feedUrl]?.let { entry ->
                if (clock() - entry.atMs < ttlMs) {
                    log("$name cache hit for $feedUrl (${entry.episodes.size} episodes)")
                    return entry.episodes
                }
                entries.remove(feedUrl)
            }
        }
        val fresh = delegate.episodesFor(feedUrl, title) ?: return null
        lock.withLock { entries[feedUrl] = Entry(clock(), fresh) }
        return fresh
    }

    companion object {
        const val DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 24
    }
}
