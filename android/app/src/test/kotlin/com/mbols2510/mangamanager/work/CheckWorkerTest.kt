package com.mbols2510.mangamanager.work

import android.app.Application
import android.app.NotificationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.mbols2510.mangamanager.data.CheckSettings
import com.mbols2510.mangamanager.data.FakeSettingsStore
import com.mbols2510.mangamanager.data.LocalTimeRange
import com.mbols2510.mangamanager.data.db.AppDatabase
import com.mbols2510.mangamanager.data.db.RoomUpdatesCache
import com.mbols2510.mangamanager.data.db.SyncState
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.data.repo.CheckResult
import com.mbols2510.mangamanager.data.repo.DefaultUpdatesRepository
import com.mbols2510.mangamanager.data.repo.UpdatesRepository
import com.mbols2510.mangamanager.notify.LocalNotifier
import com.mbols2510.mangamanager.notify.Notifier
import com.mbols2510.mangamanager.notify.update
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CheckWorkerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: Scheduler
    private lateinit var db: AppDatabase

    private val settings = FakeSettingsStore()
    private val auth = AuthState()
    private val notifier = FakeNotifier()
    private var paired = true
    private var warmUps = 0

    /** Quiet hours are evaluated in the clock's zone; Europe/Amsterdam is UTC+1 in November. */
    private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")
    private var now: Instant = at("12:00")
    private val clock: Clock get() = Clock.fixed(now, zone)

    private fun at(localTime: String): Instant =
        LocalTime.parse(localTime).atDate(java.time.LocalDate.of(2026, 11, 7)).atZone(zone).toInstant()

    private val quiet = LocalTimeRange(LocalTime.of(22, 0), LocalTime.of(7, 0))

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(app)
        scheduler = Scheduler(workManager)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun worker(
        repository: UpdatesRepository,
        attempt: Int = 0,
        notifier: Notifier = this.notifier,
        warmUpTimeoutMillis: Long = CheckWorker.WARM_UP_TIMEOUT_MILLIS,
        warmUp: suspend () -> Unit = { warmUps++ },
    ): CheckWorker {
        val deps = CheckWorker.Dependencies(
            warmUp = warmUp,
            isPaired = { paired },
            repository = repository,
            settings = settings,
            authState = auth,
            notifier = notifier,
            scheduler = scheduler,
            clock = clock,
            warmUpTimeoutMillis = warmUpTimeoutMillis,
        )
        return TestListenableWorkerBuilder.from(app, CheckWorker::class.java)
            .setWorkerFactory(CheckWorkerFactory { deps })
            .setRunAttemptCount(attempt)
            .build()
    }

    private fun run(repository: UpdatesRepository, attempt: Int = 0, notifier: Notifier = this.notifier): ListenableWorker.Result =
        runBlocking { worker(repository, attempt, notifier).doWork() }

    /** The real repository over in-memory Room, starting at cursor [cursor]. */
    private fun realRepository(api: PagedApi, cursor: Long = 0): DefaultUpdatesRepository {
        runBlocking { RoomUpdatesCache(db).setSyncState(SyncState(cursor, null, 1L)) }
        return DefaultUpdatesRepository({ api }, RoomUpdatesCache(db), auth, { now.toEpochMilli() })
    }

    private fun stored() = runBlocking { db.updateDao().allById() }

    private fun activeWork(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(Scheduler.UNIQUE_WORK_NAME).get().filterNot { it.state.isFinished }

    // ------------------------------------------------------------------ brief tests

    @Test
    fun NoChange_posts_nothing() {
        val repo = FakeUpdatesRepository(CheckResult.NoChange)
        assertEquals(ListenableWorker.Result.success(), run(repo))
        assertEquals(1, repo.checks)
        assertTrue(notifier.updateCalls.isEmpty())
        assertEquals(0, notifier.repairCalls)
    }

    @Test
    fun new_items_post_exactly_one_notification() {
        val items = (1L..3L).map { update(it) }
        val repo = FakeUpdatesRepository(CheckResult.NewItems(items))
        assertEquals(ListenableWorker.Result.success(), run(repo))
        assertEquals(1, notifier.updateCalls.size)
        assertEquals(listOf(1L, 2L, 3L), notifier.updateCalls.single().map { it.id })
        assertEquals(listOf(1L, 2L, 3L), repo.marked.sorted())
    }

    @Test
    fun offline_returns_retry() {
        val repo = FakeUpdatesRepository(CheckResult.Offline)
        assertEquals(ListenableWorker.Result.retry(), run(repo))
        assertTrue("silent", notifier.updateCalls.isEmpty() && notifier.repairCalls == 0)
        assertNull(runBlocking { settings.lastSuccessfulCheckAt() })
        // Ruling 22: one retry per period; from attempt count 1 the run ends and the next period tries again.
        assertEquals(ListenableWorker.Result.success(), run(repo, attempt = 1))
        assertEquals(ListenableWorker.Result.success(), run(repo, attempt = 5))
    }

    @Test
    fun offline_twice_in_a_period_gives_up_until_the_next_period() {
        val repo = FakeUpdatesRepository(CheckResult.Offline)
        assertEquals("first failure: one retry with backoff", ListenableWorker.Result.retry(), run(repo, attempt = 0))
        assertEquals("second failure: wait for the next period", ListenableWorker.Result.success(), run(repo, attempt = 1))
        assertEquals(2, repo.checks)
        assertTrue(notifier.updateCalls.isEmpty() && notifier.repairCalls == 0)
        assertNull("giving up is not a successful check", runBlocking { settings.lastSuccessfulCheckAt() })
    }

    @Test
    fun unauthorized_cancels_periodic_work_and_posts_repair_once() {
        scheduler.apply(CheckSettings.DEFAULT)
        assertEquals(1, activeWork().size)
        val repo = FakeUpdatesRepository(CheckResult.Unauthorized)

        assertEquals(ListenableWorker.Result.success(), run(repo))
        assertEquals(ListenableWorker.Result.success(), run(repo))

        assertEquals("one repair notification", 1, notifier.repairCalls)
        assertTrue("periodic work cancelled", activeWork().isEmpty())
        assertTrue(runBlocking { settings.needsRepair() })
        assertTrue(auth.needsRepair.value)
        assertTrue(notifier.updateCalls.isEmpty())
    }

    @Test
    fun worker_after_401_does_not_reschedule_itself() {
        scheduler.apply(CheckSettings.DEFAULT)
        val result = run(FakeUpdatesRepository(CheckResult.Unauthorized))
        assertEquals("success, never retry: a retry would run again", ListenableWorker.Result.success(), result)
        val infos = workManager.getWorkInfosForUniqueWork(Scheduler.UNIQUE_WORK_NAME).get()
        assertTrue(infos.isNotEmpty())
        assertTrue("nothing enqueued again: $infos", infos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun backlog_of_500_items_posts_one_notification_and_cursor_ends_at_last_id() {
        val api = PagedApi(total = 500)
        val repo = realRepository(api)
        assertEquals(ListenableWorker.Result.success(), run(repo))

        assertEquals("10 pages of 50", 10, api.sinces.size)
        assertEquals(1, notifier.updateCalls.size)
        assertEquals((1L..500L).toList(), notifier.updateCalls.single().map { it.id }.sorted())
        assertEquals(500L, runBlocking { db.updateDao().syncState() }!!.cursor)
        val rows = stored()
        assertEquals(500, rows.size)
        assertTrue(rows.all { it.notified })
    }

    @Test
    fun quiet_hours_cache_items_but_post_no_notification() {
        settings.setCheckSettingsBlocking(CheckSettings.DEFAULT.copy(quietHours = quiet))
        now = at("23:30")
        val repo = realRepository(PagedApi(total = 3))
        assertEquals(ListenableWorker.Result.success(), run(repo))
        assertTrue(notifier.updateCalls.isEmpty())
        val rows = stored()
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
        assertTrue("decided (suppressed) at store time", rows.all { it.notified })
        assertEquals(now.toEpochMilli(), runBlocking { settings.lastSuccessfulCheckAt() })
    }

    @Test
    fun items_cached_during_quiet_hours_are_not_notified_later_as_new() {
        settings.setCheckSettingsBlocking(CheckSettings.DEFAULT.copy(quietHours = quiet))
        val api = PagedApi(total = 3)
        val repo = realRepository(api)
        now = at("06:00")
        run(repo)
        assertTrue(notifier.updateCalls.isEmpty())

        now = at("09:00")
        api.total = 5
        run(repo)
        assertEquals(1, notifier.updateCalls.size)
        assertEquals("only the items that arrived after quiet hours", listOf(4L, 5L), notifier.updateCalls.single().map { it.id })

        run(repo)
        assertEquals(1, notifier.updateCalls.size)
    }

    @Test
    fun night_stretch_applies_2h_interval_only_inside_quiet_hours() {
        val repo = FakeUpdatesRepository(CheckResult.NoChange)
        settings.setCheckSettingsBlocking(CheckSettings.DEFAULT.copy(quietHours = quiet, quietNightStretch = true))

        // Inside quiet hours, 1 h after the last success: skipped without a request.
        now = at("23:30")
        settings.setLastBlocking(now.minus(Duration.ofHours(1)))
        assertEquals(ListenableWorker.Result.success(), run(repo))
        assertEquals(0, repo.checks)

        // Inside quiet hours, 2 h after the last success: checks.
        settings.setLastBlocking(now.minus(Duration.ofMinutes(121)))
        run(repo)
        assertEquals(1, repo.checks)

        // Outside quiet hours: always checks, even 10 min after the last success.
        now = at("12:00")
        settings.setLastBlocking(now.minus(Duration.ofMinutes(10)))
        run(repo)
        assertEquals(2, repo.checks)

        // Quiet hours without the stretch: always checks.
        settings.setCheckSettingsBlocking(CheckSettings.DEFAULT.copy(quietHours = quiet, quietNightStretch = false))
        now = at("23:30")
        settings.setLastBlocking(now.minus(Duration.ofMinutes(10)))
        run(repo)
        assertEquals(3, repo.checks)

        // Quiet hours are evaluated in the clock's zone, not UTC: 06:30 UTC is 07:30 in Amsterdam (outside),
        // 05:59 UTC is 06:59 there (inside).
        settings.setCheckSettingsBlocking(CheckSettings.DEFAULT.copy(quietHours = quiet, quietNightStretch = true))
        now = Instant.parse("2026-11-07T06:30:00Z")
        settings.setLastBlocking(now.minus(Duration.ofMinutes(10)))
        run(repo)
        assertEquals(4, repo.checks)
        now = Instant.parse("2026-11-07T05:59:00Z")
        settings.setLastBlocking(now.minus(Duration.ofMinutes(10)))
        run(repo)
        assertEquals(4, repo.checks)
    }

    @Test
    fun notification_permission_denied_still_caches_items() {
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        val local = LocalNotifier(app)

        val result = run(realRepository(PagedApi(total = 3)), notifier = local)

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        val rows = stored()
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
        assertTrue("decided: no burst of old alerts once permission is granted", rows.all { it.notified })
    }

    // ------------------------------------------------------------------ own tests

    @Test
    fun pending_notifications_from_a_crashed_check_are_notified_once() {
        val api = PagedApi(total = 3)
        val repo = realRepository(api)
        // A run that stored the items and then died before deciding them.
        assertTrue(runBlocking { repo.checkForChanges() } is CheckResult.NewItems)
        assertEquals(listOf(1L, 2L, 3L), runBlocking { repo.pendingNotifications() }.map { it.id })

        assertEquals(ListenableWorker.Result.success(), run(repo)) // the server has nothing newer: NoChange
        assertEquals(1, notifier.updateCalls.size)
        assertEquals(listOf(1L, 2L, 3L), notifier.updateCalls.single().map { it.id })
        assertTrue(stored().all { it.notified })

        run(repo)
        assertEquals("never twice", 1, notifier.updateCalls.size)
    }

    @Test
    fun pending_and_new_items_are_merged_into_one_notification() {
        val repo = FakeUpdatesRepository(CheckResult.NewItems(listOf(update(2), update(3))))
        repo.pending += listOf(update(1), update(2))
        run(repo)
        assertEquals(1, notifier.updateCalls.size)
        assertEquals(listOf(1L, 2L, 3L), notifier.updateCalls.single().map { it.id }.sorted())
    }

    @Test
    fun notifier_exception_leaves_items_pending_and_returns_success() {
        notifier.failure = IllegalStateException("platform failure")
        val repo = realRepository(PagedApi(total = 3))
        assertEquals("no retry, no tight loop", ListenableWorker.Result.success(), run(repo))
        assertEquals(1, notifier.updateCalls.size)
        val rows = stored()
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
        assertTrue("an exception is not a decision: still pending", rows.none { it.notified })
        assertEquals(listOf(1L, 2L, 3L), runBlocking { repo.pendingNotifications() }.map { it.id })
    }

    @Test
    fun pending_items_after_a_notifier_exception_are_notified_next_period() {
        notifier.failure = IllegalStateException("platform failure")
        val repo = realRepository(PagedApi(total = 3))
        run(repo)
        notifier.failure = null
        notifier.updateCalls.clear()

        assertEquals(ListenableWorker.Result.success(), run(repo)) // next period: the server has nothing newer
        assertEquals(1, notifier.updateCalls.size)
        assertEquals(listOf(1L, 2L, 3L), notifier.updateCalls.single().map { it.id })
        assertTrue(stored().all { it.notified })
        run(repo)
        assertEquals("never twice", 1, notifier.updateCalls.size)
    }

    @Test
    fun repair_notice_failure_does_not_crash_and_work_is_still_cancelled() {
        scheduler.apply(CheckSettings.DEFAULT)
        notifier.repairFailure = IllegalStateException("platform failure")
        assertEquals(ListenableWorker.Result.success(), run(FakeUpdatesRepository(CheckResult.Unauthorized)))
        assertEquals(1, notifier.repairCalls)
        assertTrue("flag persisted first", runBlocking { settings.needsRepair() })
        assertTrue(auth.needsRepair.value)
        assertTrue("periodic work cancelled", activeWork().isEmpty())
    }

    @Test
    fun cancelled_run_records_no_successful_check() {
        val repo = FakeUpdatesRepository(CheckResult.NewItems(listOf(update(1))))
        // The run is stopped while the request is in flight; OkHttp then reports an ordinary result.
        repo.onCheck = { currentCoroutineContext().cancel() }
        val worker = worker(repo)
        val thrown = runBlocking {
            val run = async(Job()) { worker.doWork() }
            runCatching { run.await() }.exceptionOrNull()
        }
        assertTrue("cancellation propagates, got $thrown", thrown is CancellationException)
        assertNull(runBlocking { settings.lastSuccessfulCheckAt() })
        assertTrue(notifier.updateCalls.isEmpty())
        assertTrue("left pending for the next run", repo.marked.isEmpty())
    }

    @Test
    fun not_paired_returns_success_without_request() {
        paired = false
        scheduler.apply(CheckSettings.DEFAULT)
        val repo = FakeUpdatesRepository(CheckResult.NoChange)
        assertEquals(ListenableWorker.Result.success(), run(repo))
        assertEquals(0, repo.checks)
        assertEquals(1, warmUps)
        assertTrue("an unpaired install must not wake the device every period", activeWork().isEmpty())
    }

    @Test
    fun cold_cache_is_awaited_before_the_check() {
        paired = false
        val repo = FakeUpdatesRepository(CheckResult.NoChange)
        val warm = worker(repo, warmUp = { delay(200); paired = true })
        assertEquals(ListenableWorker.Result.success(), runBlocking { warm.doWork() })
        assertEquals("checked once the caches were warm", 1, repo.checks)

        // A warm-up that never finishes does not hold the worker: retry later, no request.
        paired = false
        val stuck = worker(repo, warmUpTimeoutMillis = 100, warmUp = { awaitCancellation() })
        assertEquals(ListenableWorker.Result.retry(), runBlocking { stuck.doWork() })
        assertEquals(1, repo.checks)
    }

    @Test
    fun last_successful_check_recorded_on_no_change() {
        run(FakeUpdatesRepository(CheckResult.NoChange))
        assertEquals(now.toEpochMilli(), runBlocking { settings.lastSuccessfulCheckAt() })

        now = now.plus(Duration.ofMinutes(30))
        run(FakeUpdatesRepository(CheckResult.Offline))
        assertEquals("an offline run is not a success", now.minus(Duration.ofMinutes(30)).toEpochMilli(),
            runBlocking { settings.lastSuccessfulCheckAt() })
    }

    @Test
    fun repair_notification_not_reposted_when_flag_already_set() {
        runBlocking { settings.setNeedsRepair(true) }
        run(FakeUpdatesRepository(CheckResult.Unauthorized))
        assertEquals(0, notifier.repairCalls)
        assertTrue(runBlocking { settings.needsRepair() })
        assertTrue(auth.needsRepair.value)
    }

    private fun FakeSettingsStore.setCheckSettingsBlocking(value: CheckSettings) = runBlocking { setCheckSettings(value) }

    private fun FakeSettingsStore.setLastBlocking(instant: Instant) = runBlocking { setLastSuccessfulCheckAt(instant.toEpochMilli()) }
}
