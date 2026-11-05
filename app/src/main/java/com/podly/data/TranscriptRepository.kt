package com.podly.data

import com.podly.data.db.EpisodeDao
import com.podly.data.db.stableId
import kotlinx.coroutines.CancellationException
import com.podly.network.Http
import com.podly.network.TranscriptCue
import com.podly.network.TranscriptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fetches and caches a feed or local server transcript.
 *
 * Cached to disk because these are large — a real one from Acquired is 217 KB —
 * and because a transcript, once fetched, should still be there on a train. In
 * cacheDir rather than filesDir: it is re-fetchable, so the OS is welcome to
 * reclaim it under storage pressure, unlike a download the user asked for.
 */
class TranscriptRepository(
    private val episodeDao: EpisodeDao,
    private val cacheDir: File,
) {

    sealed interface Result {
        data class Ready(val cues: List<TranscriptCue>) : Result
        /** The feed declares no transcript for this episode. */
        data object None : Result
        data class Failed(val error: Throwable) : Result
    }

    suspend fun transcript(episodeId: String): Result = withContext(Dispatchers.IO) {
        val episode = episodeDao.byId(episodeId) ?: return@withContext Result.None
        val url = episode.transcriptUrl ?: return@withContext Result.None
        val cached = cacheFile(episodeId, url)
        val body = runCatching {
            cached.takeIf { it.isFile && it.length() > 0 }?.readText()
                ?: Http.get(url).also { text -> writeCache(cached, text) }
        }.getOrElse {
            if (it is CancellationException) throw it
            return@withContext Result.Failed(it)
        }
        pruneOtherVersions(episodeId, keep = cached)
        Result.Ready(TranscriptParser.parse(body, episode.transcriptType))
    }

    /** Drops the cached copy so the next read re-fetches. */
    suspend fun forget(episodeId: String) = withContext(Dispatchers.IO) {
        val url = episodeDao.byId(episodeId)?.transcriptUrl ?: return@withContext
        runCatching { cacheFile(episodeId, url).delete() }
        Unit
    }

    private fun cacheFile(episodeId: String, url: String) = File(dir(), transcriptCacheKey(episodeId, url))

    private fun dir(): File = File(cacheDir, "transcripts").apply { mkdirs() }

    /** A regenerated transcript has a new URL; its superseded copies (and the pre-URL-key file) go. */
    private fun pruneOtherVersions(episodeId: String, keep: File) {
        runCatching {
            dir().listFiles { f -> f.name == episodeId || f.name.startsWith("$episodeId-") }
                ?.filter { it.name != keep.name && !it.name.endsWith(".tmp") }
                ?.forEach { it.delete() }
        }
    }

    private fun writeCache(target: File, text: String) {
        // tmp + rename, so a cancelled fetch cannot leave a half file that would
        // then be served from cache for ever.
        val tmp = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(target)) tmp.delete()
        }.onFailure { tmp.delete() }
    }
}

/** Content-hashed server URLs must never reuse an earlier transcript body. */
fun transcriptCacheKey(episodeId: String, url: String): String = "$episodeId-${stableId(url)}"
