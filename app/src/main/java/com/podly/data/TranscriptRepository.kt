package com.podly.data

import com.podly.data.db.EpisodeDao
import com.podly.network.Http
import com.podly.network.TranscriptCue
import com.podly.network.TranscriptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fetches and caches the transcript a feed declared.
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
        val cached = cacheFile(episodeId)
        val body = runCatching {
            cached.takeIf { it.isFile && it.length() > 0 }?.readText()
                ?: Http.get(url).also { text -> writeCache(cached, text) }
        }.getOrElse { return@withContext Result.Failed(it) }
        Result.Ready(TranscriptParser.parse(body, episode.transcriptType))
    }

    /** Drops the cached copy so the next read re-fetches. */
    fun forget(episodeId: String) {
        runCatching { cacheFile(episodeId).delete() }
    }

    private fun cacheFile(episodeId: String) = File(dir(), episodeId)

    private fun dir(): File = File(cacheDir, "transcripts").apply { mkdirs() }

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
