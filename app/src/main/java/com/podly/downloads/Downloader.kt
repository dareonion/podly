package com.podly.downloads

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.podly.data.SettingsRepository
import com.podly.data.db.DownloadStatus
import com.podly.data.db.EpisodeDao
import com.podly.data.db.PodcastDao
import java.io.File
import java.util.concurrent.TimeUnit

class Downloader(
    private val context: Context,
    private val settings: SettingsRepository,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
) {
    /** An explicit (re-)download unblocks future auto-downloads of the episode. */
    suspend fun enqueue(episodeId: String) {
        episodeDao.setAutoDownloadBlocked(episodeId, false)
        enqueueKeepingBlock(episodeId)
    }

    private suspend fun enqueueKeepingBlock(episodeId: String, unmeteredOnly: Boolean = false) {
        val networkType =
            if (unmeteredOnly || settings.current().downloadWifiOnly) NetworkType.UNMETERED
            else NetworkType.CONNECTED
        episodeDao.updateDownload(episodeId, DownloadStatus.QUEUED, null)
        val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
            .setInputData(workDataOf(EpisodeDownloadWorker.KEY_EPISODE_ID to episodeId))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(networkType).build()
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
        // The user threw this download away; auto-download must not bring it back.
        episodeDao.setAutoDownloadBlocked(episodeId, true)
    }

    /** Applies the download policies from Settings; called after feed refreshes. */
    suspend fun applyPolicies() {
        autoDownloadNewest()
        autoDownloadStarted()
        deleteCompletedDownloads()
    }

    /**
     * Auto-downloads recently started (partially played, uncompleted) episodes.
     * Always constrained to unmetered networks regardless of the Wi-Fi-only
     * setting: the point is to grab an in-progress episode next time Wi-Fi is
     * available, not to re-fetch over cellular something already streaming.
     */
    suspend fun autoDownloadStarted() {
        if (!settings.current().autoDownloadStarted) return
        val since = System.currentTimeMillis() - STARTED_WINDOW_MS
        episodeDao.startedUndownloadedOnce(since, STARTED_LIMIT)
            .forEach { enqueueKeepingBlock(it.id, unmeteredOnly = true) }
    }

    /** Single-episode fast path of [autoDownloadStarted], called when playback begins. */
    suspend fun autoDownloadStartedEpisode(episodeId: String) {
        if (!settings.current().autoDownloadStarted) return
        val episode = episodeDao.byId(episodeId) ?: return
        if (episode.downloadStatus == DownloadStatus.NONE && !episode.completed && !episode.autoDownloadBlocked) {
            enqueueKeepingBlock(episode.id, unmeteredOnly = true)
        }
    }

    /**
     * Auto-downloads the N newest episodes of every subscribed podcast.
     * Skips episodes already played, failed, touched by the downloader, or whose
     * download the user removed ([EpisodeEntity.autoDownloadBlocked]).
     */
    suspend fun autoDownloadNewest() {
        val count = settings.current().autoDownloadCount
        if (count <= 0) return
        podcastDao.subscribedPodcastsOnce().forEach { podcast ->
            episodeDao.newestEpisodesForPodcast(podcast.id, count)
                .filter {
                    it.downloadStatus == DownloadStatus.NONE && !it.completed && !it.autoDownloadBlocked
                }
                .forEach { enqueueKeepingBlock(it.id) }
        }
    }

    /** Deletes downloaded files for episodes marked played, if the setting is on. */
    suspend fun deleteCompletedDownloads() {
        if (!settings.current().autoDeleteCompleted) return
        episodeDao.downloadedCompletedOnce().forEach { episode ->
            episode.localFilePath?.let { File(it).delete() }
            episodeDao.updateDownload(episode.id, DownloadStatus.NONE, null)
        }
    }

    private fun workName(episodeId: String) = "download_$episodeId"

    private companion object {
        /** How far back "recently started" reaches. */
        val STARTED_WINDOW_MS = TimeUnit.DAYS.toMillis(30)
        const val STARTED_LIMIT = 20
    }
}
