package com.mbols2510.mangamanager.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mbols2510.mangamanager.data.CheckSettings
import java.util.concurrent.TimeUnit

/**
 * The ONE unique periodic background check (spec 6). No expedited work, no foreground service, no alarms.
 *
 * [apply] enqueues [UNIQUE_WORK_NAME] with [ExistingPeriodicWorkPolicy.UPDATE]: an existing schedule keeps its
 * enqueue time and period count (WorkManager copies them into the updated spec), so calling it on every app start
 * does not push the next run back; only changed parameters take effect. `intervalMinutes == null` cancels instead.
 */
class Scheduler(private val workManager: WorkManager) {

    fun apply(settings: CheckSettings) {
        if (settings.intervalMinutes == null) {
            cancel()
            return
        }
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request(settings))
    }

    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "manga_update_check"

        /** First retry after 30 s, doubling; the worker stops retrying after [CheckWorker.MAX_RETRY_ATTEMPTS]. */
        const val BACKOFF_SECONDS = 30L

        fun clampInterval(minutes: Int): Int =
            minutes.coerceIn(CheckSettings.MIN_INTERVAL_MINUTES, CheckSettings.MAX_INTERVAL_MINUTES)

        /** About a quarter of the interval, never below WorkManager's minimum flex (5 min). */
        fun flexMillis(intervalMillis: Long): Long =
            (intervalMillis / 4).coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS)

        internal fun request(settings: CheckSettings): PeriodicWorkRequest {
            val intervalMillis = TimeUnit.MINUTES.toMillis(
                clampInterval(settings.intervalMinutes ?: CheckSettings.DEFAULT_INTERVAL_MINUTES).toLong(),
            )
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            // No initial delay: with a flex window the first run lands in the last flex of the first period.
            return PeriodicWorkRequestBuilder<CheckWorker>(
                intervalMillis, TimeUnit.MILLISECONDS,
                flexMillis(intervalMillis), TimeUnit.MILLISECONDS,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }
}
