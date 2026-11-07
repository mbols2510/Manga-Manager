package com.mbols2510.mangamanager.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.mbols2510.mangamanager.data.SettingsStore
import com.mbols2510.mangamanager.data.db.UpdateEntity
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.data.repo.CheckResult
import com.mbols2510.mangamanager.data.repo.UpdatesRepository
import com.mbols2510.mangamanager.notify.Notifier
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The periodic background check (spec 5.1, 5.3, 6). One short run per period: no foreground service, no expedited
 * work, no wake lock of its own. Dependencies come from [CheckWorkerFactory].
 *
 * Notification decisions follow ruling 21: every stored item whose decision is not taken (`notified == false`) is
 * decided in this run (posted, suppressed by quiet hours, or notifications unavailable) and then marked notified.
 */
class CheckWorker(
    context: Context,
    params: WorkerParameters,
    private val deps: Dependencies,
) : CoroutineWorker(context, params) {

    /** What the worker needs; production values come from `AppContainer.checkWorkerDependencies()`. */
    class Dependencies(
        /** Fills the in-memory pairing caches (a cold cache makes the repository answer Offline). */
        val warmUp: suspend () -> Unit,
        /** True when a pairing (server URL and token) is published; read after [warmUp]. */
        val isPaired: () -> Boolean,
        val repository: UpdatesRepository,
        val settings: SettingsStore,
        val authState: AuthState,
        val notifier: Notifier,
        val scheduler: Scheduler,
        /** Time and time zone (quiet hours use [Clock.getZone], the device's default zone in production). */
        val clock: Clock,
        /** How long [warmUp] may take before the run gives up and retries later. */
        val warmUpTimeoutMillis: Long = WARM_UP_TIMEOUT_MILLIS,
    )

    // TDD stub (red commit): does nothing.
    override suspend fun doWork(): Result = Result.success()

    @Suppress("unused")
    private suspend fun doWorkReal(): Result {
        // A stuck warm-up (Keystore, storage) must not hold the worker: give up and let the backoff retry.
        val warmed = try {
            withTimeoutOrNull(deps.warmUpTimeoutMillis) { deps.warmUp() } != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!warmed) return retryOrGiveUp()
        if (!deps.isPaired()) return Result.success() // nothing to check; scheduling is the pairing flow's job

        val settings = deps.settings.checkSettings()
        val now = deps.clock.instant()
        val inQuietHours = settings.quietHours?.contains(now.atZone(deps.clock.zone).toLocalTime()) == true

        if (inQuietHours && settings.quietNightStretch) {
            val last = deps.settings.lastSuccessfulCheckAt()
            val sinceLast = last?.let { now.toEpochMilli() - it }
            if (sinceLast != null && sinceLast in 0L until NIGHT_STRETCH_MILLIS) return Result.success() // no request
        }

        // Items stored by an earlier run that ended before deciding them (ruling 21, Task 7 review M5).
        val pending = deps.repository.pendingNotifications()
        val result = deps.repository.checkForChanges()
        // A stopped run can surface as Offline (a cancelled OkHttp call is an IOException): never count it.
        currentCoroutineContext().ensureActive()

        return when (result) {
            CheckResult.NoChange -> {
                deps.settings.setLastSuccessfulCheckAt(deps.clock.millis())
                decide(pending, inQuietHours)
                Result.success()
            }
            is CheckResult.NewItems -> {
                deps.settings.setLastSuccessfulCheckAt(deps.clock.millis())
                decide((pending + result.items).distinctBy { it.id }, inQuietHours)
                Result.success()
            }
            CheckResult.Offline -> {
                decide(pending, inQuietHours)
                retryOrGiveUp()
            }
            CheckResult.Unauthorized -> {
                val alreadyFlagged = deps.settings.needsRepair()
                deps.settings.setNeedsRepair(true)
                deps.authState.markUnauthorized()
                if (!alreadyFlagged) deps.notifier.notifyRepairNeeded()
                // Last: cancelling the unique work also stops this run. Nothing reschedules until a new pairing.
                deps.scheduler.cancel()
                Result.success()
            }
        }
    }

    /** Silent retry with exponential backoff, but not forever: the next period tries again anyway. */
    private fun retryOrGiveUp(): Result =
        if (runAttemptCount >= MAX_RETRY_ATTEMPTS) Result.success() else Result.retry()

    /** One notification for all [items] unless quiet hours suppress it; either way the decision is taken. */
    private suspend fun decide(items: List<UpdateEntity>, inQuietHours: Boolean) {
        if (items.isEmpty()) return
        // Posting and marking belong together: a stop in between would only re-post once (same tag, no re-alert).
        withContext(NonCancellable) {
            if (!inQuietHours) deps.notifier.notifyUpdates(items) // false = unavailable: decided as well
            deps.repository.markNotified(items.map { it.id })
        }
    }

    companion object {
        /** `runAttemptCount` from which a failed run returns success instead of retry (max backoff 30 s * 2^4 = 8 min). */
        const val MAX_RETRY_ATTEMPTS = 5
        const val WARM_UP_TIMEOUT_MILLIS = 20_000L
        val NIGHT_STRETCH_MILLIS: Long = Duration.ofHours(2).toMillis()
    }
}

/** Builds [CheckWorker] with its dependencies; any other worker class falls back to WorkManager's default. */
class CheckWorkerFactory(private val dependencies: () -> CheckWorker.Dependencies) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == CheckWorker::class.java.name) {
            CheckWorker(appContext, workerParameters, dependencies())
        } else {
            null
        }
}
