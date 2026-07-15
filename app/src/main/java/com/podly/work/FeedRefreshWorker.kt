package com.podly.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.podly.appGraph
import java.util.concurrent.TimeUnit

/** Periodically re-parses subscribed feeds so new episodes appear in the library. */
class FeedRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        graph.podcasts.refreshAllSubscribed()
        graph.downloader.applyPolicies()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "feed_refresh",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
