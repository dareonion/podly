package com.podly.downloads

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.podly.appGraph
import com.podly.data.db.DownloadStatus
import com.podly.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

class EpisodeDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val episodeId = inputData.getString(KEY_EPISODE_ID) ?: return Result.failure()
        val dao = applicationContext.appGraph.database.episodeDao()
        val episode = dao.byId(episodeId) ?: return Result.failure()

        dao.updateDownload(episodeId, DownloadStatus.DOWNLOADING, null)
        val target = targetFile(applicationContext, episodeId, episode.audioUrl)

        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(episode.audioUrl)
                    .header("User-Agent", "Podly/1.0")
                    .build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty body")
                    target.parentFile?.mkdirs()
                    val tmp = File(target.path + ".part")
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    if (!tmp.renameTo(target)) throw IOException("rename failed")
                }
            }
            dao.updateDownload(episodeId, DownloadStatus.DONE, target.path)
            Result.success()
        } catch (e: Exception) {
            File(target.path + ".part").delete()
            target.delete()
            if (runAttemptCount < 2) {
                dao.updateDownload(episodeId, DownloadStatus.QUEUED, null)
                Result.retry()
            } else {
                dao.updateDownload(episodeId, DownloadStatus.FAILED, null)
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_EPISODE_ID = "episodeId"

        fun targetFile(context: Context, episodeId: String, audioUrl: String): File {
            val extension = audioUrl.substringBefore('?').substringAfterLast('.', "mp3")
                .takeIf { it.length in 2..4 } ?: "mp3"
            return File(File(context.filesDir, "episodes"), "$episodeId.$extension")
        }
    }
}
