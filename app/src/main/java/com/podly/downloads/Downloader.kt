package com.podly.downloads

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.podly.data.db.DownloadStatus
import com.podly.data.db.EpisodeDao
import java.io.File
import java.util.concurrent.TimeUnit

class Downloader(
    private val context: Context,
    private val episodeDao: EpisodeDao,
) {
    suspend fun enqueue(episodeId: String) {
        episodeDao.updateDownload(episodeId, DownloadStatus.QUEUED, null)
        val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
            .setInputData(workDataOf(EpisodeDownloadWorker.KEY_EPISODE_ID to episodeId))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(episodeId), ExistingWorkPolicy.KEEP, request)
    }

    suspend fun remove(episodeId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(episodeId))
        episodeDao.byId(episodeId)?.localFilePath?.let { File(it).delete() }
        episodeDao.updateDownload(episodeId, DownloadStatus.NONE, null)
    }

    private fun workName(episodeId: String) = "download_$episodeId"
}
