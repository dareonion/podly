package com.podly.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.podly.AppGraph
import com.podly.R
import com.podly.appGraph
import com.podly.data.CachedRecentEpisodePick
import com.podly.data.CachedRecentEpisodes
import com.anthropic.errors.AnthropicServiceException
import com.podly.data.db.PodcastEntity
import com.podly.network.ai.RecentEpisodeWindow
import com.podly.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

/**
 * Runs the slow, web-search-backed "best recent episodes" AI call off the UI.
 *
 * The call routinely takes minutes, which a phone radio won't keep an idle socket
 * alive for — in the foreground it stranded a spinner that the user couldn't escape.
 * As background work it survives the screen sleeping, the app backgrounding, and even
 * process death; a dropped connection retries (via the network constraint + backoff)
 * instead of failing outright. The result lands in [com.podly.data.AiPicksCache] and a
 * notification fires when it's ready.
 */
class RecentEpisodesWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val window = windowOrNull() ?: return Result.failure()
        val graph = applicationContext.appGraph
        Log.i(TAG, "doWork start: window=$window attempt=$runAttemptCount")
        // Run as a foreground service: the AI web-search call takes minutes, and a
        // plain background job gets stopped by JobScheduler (~1 min in) the moment
        // the app isn't foregrounded, then retried — which stranded the UI spinner.
        // Degrade to background execution if the OS refuses to start the FGS.
        runCatching { setForeground(foregroundInfo(window)) }
            .onFailure { Log.w(TAG, "setForeground failed; running in background", it) }
        return try {
            val picks = graph.aiRecommender.recentEpisodes(window)
            val resolved = coroutineScope {
                picks.map { pick ->
                    async { CachedRecentEpisodePick.of(pick, resolve(graph, pick.podcastTitle)) }
                }.awaitAll()
            }
            graph.aiPicksCache.saveRecentEpisodes(
                window,
                CachedRecentEpisodes(picks = resolved, fetchedAtMs = System.currentTimeMillis()),
            )
            Log.i(TAG, "doWork success: ${resolved.size} picks for $window")
            notify(window, resolved.size, failed = false)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // e.g. "Add your Anthropic API key in Settings first." — retrying won't help.
            Log.e(TAG, "doWork giving up (config error) for $window", e)
            notify(window, 0, failed = true, reason = e.message)
            Result.failure(workDataOf(KEY_ERROR to e.message.orEmpty()))
        } catch (e: AnthropicServiceException) {
            // A 4xx (bad/expired key, no credits, malformed request) will fail again on
            // retry — fail fast and tell the user why, instead of looping the spinner.
            // Only rate limits and server errors are worth re-running.
            val retryable = e.statusCode() == 429 || e.statusCode() == 408 ||
                e.statusCode() == 409 || e.statusCode() >= 500
            if (retryable && runAttemptCount < MAX_ATTEMPTS) {
                Log.w(TAG, "doWork attempt $runAttemptCount got HTTP ${e.statusCode()}; will retry", e)
                Result.retry()
            } else {
                val reason = anthropicReason(e)
                Log.e(TAG, "doWork giving up (HTTP ${e.statusCode()}) for $window", e)
                notify(window, 0, failed = true, reason = reason)
                Result.failure(workDataOf(KEY_ERROR to reason.orEmpty()))
            }
        } catch (e: Exception) {
            // Transient network/SDK errors (the recommender already retried internally);
            // let WorkManager re-run once the network is back rather than giving up.
            if (runAttemptCount < MAX_ATTEMPTS) {
                Log.w(TAG, "doWork attempt $runAttemptCount failed for $window; will retry", e)
                Result.retry()
            } else {
                Log.e(TAG, "doWork failed after $MAX_ATTEMPTS attempts for $window", e)
                notify(window, 0, failed = true)
                Result.failure()
            }
        }
    }

    // Supplied to WorkManager if this ever runs expedited; also reused by doWork.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(windowOrNull())

    private fun windowOrNull(): RecentEpisodeWindow? =
        inputData.getString(KEY_WINDOW)
            ?.let { runCatching { RecentEpisodeWindow.valueOf(it) }.getOrNull() }

    /** Ongoing notification that backs the foreground service while the call runs. */
    private fun foregroundInfo(window: RecentEpisodeWindow?): ForegroundInfo {
        val ctx = applicationContext
        ensureProgressChannel(ctx)
        val period = window?.label?.lowercase() ?: "selected period"
        val notification = NotificationCompat.Builder(ctx, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Finding recent episodes")
            .setContentText("Searching for the best episodes from the past $period…")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    /** Mirrors DiscoverViewModel's directory match so the cached result carries artwork. */
    private suspend fun resolve(graph: AppGraph, title: String): PodcastEntity? =
        runCatching { graph.podcasts.search(title) }.getOrNull()?.let { results ->
            results.firstOrNull { it.title.equals(title, ignoreCase = true) }
                ?: results.firstOrNull()
        }

    /** Pulls the human-readable "message" out of an Anthropic error's JSON body. */
    private fun anthropicReason(e: AnthropicServiceException): String? {
        val raw = e.message ?: return null
        return Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
            ?: raw.take(140)
    }

    private fun notify(
        window: RecentEpisodeWindow,
        count: Int,
        failed: Boolean,
        reason: String? = null,
    ) {
        val ctx = applicationContext
        val manager = NotificationManagerCompat.from(ctx)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(ctx)

        val pending = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val period = window.label.lowercase()
        val (title, text) = if (failed) {
            "Recent episodes" to (
                reason?.takeIf { it.isNotBlank() }
                    ?.let { "Couldn't load episodes for the past $period: $it" }
                    ?: "Couldn't load episodes for the past $period. Open Podly to retry."
                )
        } else {
            "Recent episodes ready" to
                "Found $count worthwhile ${if (count == 1) "episode" else "episodes"} from the past $period."
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            manager.notify(NOTIFICATION_ID_BASE + window.ordinal, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the post; result is still cached.
        }
    }

    companion object {
        private const val TAG = "RecentEpisodesWorker"
        const val KEY_WINDOW = "window"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 3
        private const val CHANNEL_ID = "recent_episodes"
        private const val PROGRESS_CHANNEL_ID = "recent_episodes_progress"
        private const val NOTIFICATION_ID_BASE = 4200
        private const val PROGRESS_NOTIFICATION_ID = 4300

        fun workName(window: RecentEpisodeWindow) = "recent_episodes_${window.name}"

        /** Kicks off a background fetch. [force] replaces any in-flight/cached run. */
        fun enqueue(context: Context, window: RecentEpisodeWindow, force: Boolean) {
            val request = OneTimeWorkRequestBuilder<RecentEpisodesWorker>()
                .setInputData(workDataOf(KEY_WINDOW to window.name))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(window),
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun workInfoFlow(context: Context, window: RecentEpisodeWindow) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(window))

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Recent episode picks",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
        }

        /** Low-importance channel for the unobtrusive "finding episodes…" progress note. */
        private fun ensureProgressChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(PROGRESS_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        PROGRESS_CHANNEL_ID,
                        "Finding recent episodes",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
    }
}
