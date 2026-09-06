package com.podly.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.util.Log
import com.podly.appGraph
import com.podly.radio.RadioProfiles
import java.util.concurrent.TimeUnit

/**
 * Keeps the generated radio pools fresh in the background.
 *
 * Separate from [FeedRefreshWorker] rather than folded into it: that one is
 * enqueued with KEEP, so changing its interval or work would not take effect on
 * an installed device.
 */
class RadioPoolWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        var attempted = 0
        var failed = 0
        RadioProfiles.ALL.forEach { profile ->
            attempted++
            runCatching { graph.radio.syncPool(profile.id) }
                .onFailure { error ->
                    failed++
                    Log.w(TAG, "pool sync failed for ${profile.id}", error)
                }
        }
        // Individual flakiness isn't worth a retry; a total failure is.
        return if (attempted > 0 && failed == attempted) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "RadioPoolWorker"
        private const val UNIQUE = "radio_pool_refresh"
        private const val UNIQUE_NOW = "radio_pool_now"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RadioPoolWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Used when radio is opened with an empty or stale pool. */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<RadioPoolWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
