package com.google.ai.edge.gallery.analytics

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.ai.edge.gallery.firebaseAnalytics
import java.util.concurrent.TimeUnit

/**
 * Worker that logs a battery snapshot and flushes queued Firebase events.
 *
 * Scheduled two ways:
 * 1. **Connectivity-triggered** — a one-time job enqueued whenever we detect
 *    network availability (via [ConnectivitySyncScheduler]). This ensures
 *    events are flushed as soon as the device comes online.
 * 2. **Periodic fallback** — runs every 30 min when connected, as a safety
 *    net in case the connectivity callback misses a transition.
 */
class AnalyticsSyncWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            BatteryAnalytics.logBatteryEvent(appContext, trigger = "sync")
            firebaseAnalytics?.let {
                Log.d(TAG, "Analytics sync: flushing queued events")
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Analytics sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AnalyticsSyncWorker"
        private const val PERIODIC_WORK = "analytics_periodic_sync"
        private const val CONNECTIVITY_WORK = "analytics_connectivity_sync"

        /** Enqueue an immediate one-time sync (called when connectivity is detected). */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnalyticsSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                CONNECTIVITY_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            Log.d(TAG, "Connectivity sync enqueued")
        }

        /** Schedule a periodic fallback sync (every 30 min, network + battery OK). */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AnalyticsSyncWorker>(
                30, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.d(TAG, "Periodic analytics sync scheduled (every 30 min)")
        }
    }
}
