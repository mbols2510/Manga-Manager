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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What Settings and app start need from the scheduler; [Scheduler] is the WorkManager implementation (test seam). */
interface CheckScheduler {
    /** Idempotent: an unchanged schedule keeps its timing; a null interval cancels. */
    fun apply(settings: CheckSettings)

    fun cancel()

    /** Cancels and waits (bounded) until WorkManager has recorded the cancellation (unpairing). */
    suspend fun cancelAndAwait() = cancel()
}

/**
 * The ONE unique periodic background check (spec 6). No expedited work, no foreground service, no alarms.
 *
 * [apply] enqueues [UNIQUE_WORK_NAME] with [ExistingPeriodicWorkPolicy.UPDATE]: an existing schedule keeps its
 * enqueue time and period count (WorkManager copies them into the updated spec), so calling it on every app start
 * does not push the next run back; only changed parameters take effect. `intervalMinutes == null` cancels instead.
 */
class Scheduler(private val workManager: WorkManager) : CheckScheduler {

    override fun apply(settings: CheckSettings) {
        if (settings.intervalMinutes == null) {
            cancel()
            return
        }
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request(settings))
    }

    override fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /**
     * Waits on IO for the cancel operation, at most [CANCEL_TIMEOUT_SECONDS]; a timeout throws. A run already in
     * progress may still finish its current step: the pairing generation keeps it from writing anything.
     */
    override suspend fun cancelAndAwait() {
        val operation = workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        withContext(Dispatchers.IO) { operation.result.get(CANCEL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "manga_update_check"

        /** The single retry per period comes 30 s after a failure (the worker gives up from [CheckWorker.MAX_RETRY_ATTEMPTS]). */
        const val BACKOFF_SECONDS = 30L

        /** How long an unpair waits for WorkManager to record the cancellation. */
        const val CANCEL_TIMEOUT_SECONDS = 10L

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
