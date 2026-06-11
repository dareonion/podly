package com.podly.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Process-wide streaming cache: scrubbing back replays cached bytes instead of
 * re-downloading, and resume-from-position starts instantly. Must be a singleton —
 * SimpleCache throws if two instances open the same directory.
 */
@OptIn(UnstableApi::class)
object MediaCache {

    private const val MAX_BYTES = 1024L * 1024 * 1024 // 1 GB, LRU-evicted

    private var cache: SimpleCache? = null

    @Synchronized
    fun get(context: Context): SimpleCache =
        cache ?: SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
}
