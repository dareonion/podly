package com.podly.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
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
        val window = inputData.getString(KEY_WINDOW)
            ?.let { runCatching { RecentEpisodeWindow.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val graph = applicationContext.appGraph
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
            notify(window, resolved.size, failed = false)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // e.g. "Add your Anthropic API key in Settings first." — retrying won't help.
            notify(window, 0, failed = true)
            Result.failure()
        } catch (e: Exception) {
            // Transient network/SDK errors (the recommender already retried internally);
            // let WorkManager re-run once the network is back rather than giving up.
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                notify(window, 0, failed = true)
                Result.failure()
            }
        }
    }

    /** Mirrors DiscoverViewModel's directory match so the cached result carries artwork. */
    private suspend fun resolve(graph: AppGraph, title: String): PodcastEntity? =
        runCatching { graph.podcasts.search(title) }.getOrNull()?.let { results ->
            results.firstOrNull { it.title.equals(title, ignoreCase = true) }
                ?: results.firstOrNull()
        }

    private fun notify(window: RecentEpisodeWindow, count: Int, failed: Boolean) {
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
            "Recent episodes" to
                "Couldn't load episodes for the past $period. Open Podly to retry."
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
        const val KEY_WINDOW = "window"
        private const val MAX_ATTEMPTS = 3
        private const val CHANNEL_ID = "recent_episodes"
        private const val NOTIFICATION_ID_BASE = 4200

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
    }
}
