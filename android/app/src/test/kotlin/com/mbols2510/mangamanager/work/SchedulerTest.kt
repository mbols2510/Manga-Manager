package com.mbols2510.mangamanager.work

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.mbols2510.mangamanager.data.CheckSettings
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: Scheduler

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = Scheduler(workManager)
    }

    private fun infos(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(Scheduler.UNIQUE_WORK_NAME).get()

    private fun active(): List<WorkInfo> = infos().filterNot { it.state.isFinished }

    private fun only(): WorkInfo {
        val active = active()
        assertEquals("exactly one active unique periodic work", 1, active.size)
        return active.single()
    }

    private fun minutes(m: Long) = TimeUnit.MINUTES.toMillis(m)

    private fun settings(interval: Int? = 30, wifiOnly: Boolean = false) =
        CheckSettings.DEFAULT.copy(intervalMinutes = interval, wifiOnly = wifiOnly)

    @Test
    fun interval_below_15_is_clamped_to_15() {
        scheduler.apply(settings(interval = 5))
        assertEquals(minutes(15), only().periodicityInfo!!.repeatIntervalMillis)
        assertEquals(15, Scheduler.clampInterval(1))
    }

    @Test
    fun interval_above_360_is_clamped() {
        scheduler.apply(settings(interval = 1_000))
        assertEquals(minutes(360), only().periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun null_interval_cancels_work() {
        scheduler.apply(settings(interval = 30))
        only()
        scheduler.apply(settings(interval = null))
        assertTrue(active().isEmpty())
        assertTrue(infos().all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun wifi_only_uses_unmetered() {
        scheduler.apply(settings(wifiOnly = false))
        assertEquals(NetworkType.CONNECTED, only().constraints.requiredNetworkType)
        scheduler.apply(settings(wifiOnly = true))
        assertEquals(NetworkType.UNMETERED, only().constraints.requiredNetworkType)
    }

    @Test
    fun exactly_one_unique_work_exists_after_two_applies() {
        scheduler.apply(settings(interval = 30))
        val first = only()
        scheduler.apply(settings(interval = 60, wifiOnly = true))
        val second = only()
        assertEquals("UPDATE keeps the same work", first.id, second.id)
        assertEquals(1, infos().size)
        assertEquals(minutes(60), second.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.UNMETERED, second.constraints.requiredNetworkType)
    }

    @Test
    fun battery_not_low_constraint_present() {
        scheduler.apply(settings())
        val constraints = only().constraints
        assertTrue(constraints.requiresBatteryNotLow())
        assertFalse("no charging requirement", constraints.requiresCharging())
    }

    @Test
    fun flex_is_about_a_quarter_of_the_interval() {
        scheduler.apply(settings(interval = 60))
        assertEquals(minutes(15), only().periodicityInfo!!.flexIntervalMillis)
        scheduler.apply(settings(interval = 360))
        assertEquals(minutes(90), only().periodicityInfo!!.flexIntervalMillis)
        // A quarter of 15 min is below WorkManager's minimum flex of 5 min.
        scheduler.apply(settings(interval = 15))
        assertEquals(minutes(5), only().periodicityInfo!!.flexIntervalMillis)
    }

    @Test
    fun default_interval_is_30_minutes() {
        assertEquals(30, CheckSettings.DEFAULT.intervalMinutes)
        val before = System.currentTimeMillis()
        scheduler.apply(CheckSettings.DEFAULT)
        val info = only()
        assertEquals(minutes(30), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        // No initial delay beyond the flex window: the first run is due within one interval.
        assertTrue(
            "first run within one interval (next=${info.nextScheduleTimeMillis - before} ms)",
            info.nextScheduleTimeMillis <= before + minutes(30) + 1_000,
        )
    }

    @Test
    fun update_keeps_the_existing_schedule_when_parameters_are_unchanged() {
        scheduler.apply(CheckSettings.DEFAULT)
        val first = only()
        Thread.sleep(50)
        scheduler.apply(CheckSettings.DEFAULT) // what every app start does
        val second = only()
        assertEquals(first.id, second.id)
        assertEquals("an app start must not push the next run back", first.nextScheduleTimeMillis, second.nextScheduleTimeMillis)
    }

    // WorkInfo does not expose the backoff or the expedited flag, so this reads the request's WorkSpec (a
    // library-group API) in this one test.
    @SuppressLint("RestrictedApi")
    @Test
    fun request_uses_capped_exponential_backoff_and_no_expedited_or_initial_delay() {
        val spec = Scheduler.request(CheckSettings.DEFAULT).workSpec
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(30), spec.backoffDelayDuration)
        assertEquals(0L, spec.initialDelay)
        assertFalse(spec.expedited)
        assertEquals(CheckWorker::class.java.name, spec.workerClassName)
        // The worker retries once per period (ruling 22), so the only backoff (30 s) stays far below the 15 min minimum.
        val longest = TimeUnit.SECONDS.toMillis(30) shl (CheckWorker.MAX_RETRY_ATTEMPTS - 1)
        assertTrue(longest < minutes(15))
    }
}
