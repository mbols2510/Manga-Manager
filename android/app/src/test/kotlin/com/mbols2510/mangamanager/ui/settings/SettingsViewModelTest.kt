package com.mbols2510.mangamanager.ui.settings

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import coil3.ImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import com.mbols2510.mangamanager.AppContainer
import com.mbols2510.mangamanager.core.TokenStore
import com.mbols2510.mangamanager.data.CheckSettings
import com.mbols2510.mangamanager.data.FakeSettingsStore
import com.mbols2510.mangamanager.data.LocalTimeRange
import com.mbols2510.mangamanager.data.SettingsStore
import com.mbols2510.mangamanager.data.db.RoomUpdatesCache
import com.mbols2510.mangamanager.data.db.SyncState
import com.mbols2510.mangamanager.data.db.UpdateEntity
import com.mbols2510.mangamanager.ui.common.CoverImageLoader
import com.mbols2510.mangamanager.notify.Notifier
import com.mbols2510.mangamanager.ui.pairing.FakeTokenStore
import com.mbols2510.mangamanager.work.FakeNotifier
import com.mbols2510.mangamanager.work.CheckScheduler
import com.mbols2510.mangamanager.work.Scheduler
import java.io.IOException
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Records what reaches the real [Scheduler] (and in which order). */
class RecordingScheduler(private val delegate: CheckScheduler? = null) : CheckScheduler {
    val calls = mutableListOf<String>()

    override fun apply(settings: CheckSettings) {
        calls += "apply:${settings.intervalMinutes}"
        delegate?.apply(settings)
    }

    override fun cancel() {
        calls += "cancel"
        delegate?.cancel()
    }

    override suspend fun cancelAndAwait() {
        calls += "cancelAndAwait"
        delegate?.cancelAndAwait()
    }
}

/**
 * Settings logic on the real container pieces: real [Scheduler] over the WorkManager test instance, real Room (the
 * container's database), a real Coil loader for the cache checks; only the token store and settings store are fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var workManager: WorkManager
    private val containers = mutableListOf<AppContainer>()
    private val loader: ImageLoader by lazy { CoverImageLoader.build(context) { OkHttpClient() } }
    private lateinit var recording: RecordingScheduler

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        containers.forEach { runCatching { it.database.close() } }
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ helpers

    private fun active(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(Scheduler.UNIQUE_WORK_NAME).get().filterNot { it.state.isFinished }

    private fun only(): WorkInfo = active().also { assertEquals("one active unique periodic work", 1, it.size) }.single()

    private fun minutes(m: Long) = TimeUnit.MINUTES.toMillis(m)

    private fun container(
        settings: SettingsStore = FakeSettingsStore(URL),
        tokens: TokenStore = FakeTokenStore().apply { token = TOKEN },
        notifier: Notifier? = null,
        scheduler: CheckScheduler? = null,
    ): AppContainer = AppContainer(context, settings, tokens, imageLoader = { loader }, notifierOverride = notifier, schedulerOverride = scheduler).also {
        containers += it
        runBlocking { it.warmUp() }
    }

    private fun vm(
        c: AppContainer,
        status: () -> NotificationStatus = { STATUS_ON },
    ): SettingsViewModel {
        recording = RecordingScheduler(c.scheduler)
        return SettingsViewModel(
            settings = c.settingsStore,
            authState = c.authState,
            scheduler = recording,
            mayScheduleChecks = c::mayScheduleChecks,
            unpairAction = { c.unpair() },
            readNotificationStatus = status,
            io = dispatcher,
        )
    }

    private fun stored(c: AppContainer): CheckSettings = runBlocking { c.settingsStore.checkSettings() }

    private fun entity(id: Long) =
        UpdateEntity(id, "Title $id", "Manga", false, listOf("c$id"), "2026-11-01T00:00:00+00:00", "thumb/$id.png", null, false)

    private fun seedRoom(c: AppContainer) = runBlocking {
        RoomUpdatesCache(c.database).applyChanges(listOf(entity(1), entity(2)), SyncState(7, "\"etag-7\"", 1L))
        assertEquals(2, c.database.updateDao().allById().size)
        assertNotNull(c.database.updateDao().syncState())
    }

    private fun seedImageCaches() {
        loader.memoryCache!![MemoryCache.Key("cover")] =
            MemoryCache.Value(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImage())
        val disk = loader.diskCache!!
        val editor = disk.openEditor("cover")!!
        disk.fileSystem.write(editor.metadata) { writeUtf8("meta") }
        disk.fileSystem.write(editor.data) { writeUtf8("png") }
        editor.commit()
        assertNotNull(loader.memoryCache!![MemoryCache.Key("cover")])
        disk.openSnapshot("cover")!!.close()
    }

    private fun assertImageCachesEmpty() {
        assertNull("memory cache cleared", loader.memoryCache!![MemoryCache.Key("cover")])
        assertEquals(0L, loader.memoryCache!!.size)
        assertNull("disk cache cleared", loader.diskCache!!.openSnapshot("cover"))
    }

    /** Waits (bounded) for the unpair to finish: Room and Coil switch threads, so the result is not synchronous. */
    private fun awaitUnpaired(vm: SettingsViewModel) = runBlocking {
        withTimeout(10_000) { vm.events.first { it == SettingsEvent.Unpaired } }
    }

    // ------------------------------------------------------------------ plan tests

    @Test
    fun changing_interval_reschedules_via_scheduler() {
        val c = container()
        val vm = vm(c)

        vm.setInterval(60)
        assertEquals(60, stored(c).intervalMinutes)
        assertEquals(minutes(60), only().periodicityInfo!!.repeatIntervalMillis)

        vm.setInterval(120)
        assertEquals(120, stored(c).intervalMinutes)
        assertEquals(minutes(120), only().periodicityInfo!!.repeatIntervalMillis)
        assertEquals("apply only (UPDATE policy), never cancel + apply", listOf("apply:60", "apply:120"), recording.calls)
        assertEquals(120, vm.state.value.check.intervalMinutes)
    }

    @Test
    fun off_cancels_work() {
        val c = container()
        runBlocking { c.scheduleChecksIfPaired() }
        only()
        val vm = vm(c)

        vm.setInterval(null)

        assertNull(stored(c).intervalMinutes)
        assertTrue("no active check left", active().isEmpty())
        assertEquals(listOf("apply:null"), recording.calls)
        assertNull(vm.state.value.check.intervalMinutes)
    }

    @Test
    fun unpair_clears_token_cursor_cache_and_cancels_work() {
        val tokens = FakeTokenStore().apply { token = TOKEN }
        val settings = FakeSettingsStore(
            URL,
            checkSettings = CheckSettings.DEFAULT.copy(intervalMinutes = 60, wifiOnly = true),
            lastSuccessfulCheckAt = 5_000L,
        )
        val c = container(settings, tokens)
        runBlocking { c.scheduleChecksIfPaired() }
        only()
        seedRoom(c)
        val vm = vm(c)

        vm.requestUnpair()
        assertTrue(vm.state.value.confirmingUnpair)
        vm.unpair()
        awaitUnpaired(vm)

        assertNull("credentials holder cleared", c.credentials.get())
        assertNull("token removed", tokens.token)
        assertNull(c.tokenStore.cachedToken)
        runBlocking {
            assertNull("server URL removed", settings.serverUrl())
            assertEquals("check settings back to defaults", CheckSettings.DEFAULT, settings.checkSettings())
            assertNull("last successful check removed", settings.lastSuccessfulCheckAt())
            assertFalse(settings.needsRepair())
            assertTrue("cached rows removed", c.database.updateDao().allById().isEmpty())
            assertNull("cursor and ETag removed", c.database.updateDao().syncState())
        }
        assertTrue("periodic work cancelled", active().isEmpty())
        assertFalse(c.authState.needsRepair.value)
        assertFalse(vm.state.value.unpairing)
    }

    // ------------------------------------------------------------------ check settings

    @Test
    fun interval_presets_map_to_check_settings() {
        assertEquals(listOf(null, 15, 30, 60, 120, 360), SettingsViewModel.INTERVAL_PRESETS)
        val c = container()
        val vm = vm(c)
        assertEquals("default 30 minutes", 30, vm.state.value.check.intervalMinutes)

        for (preset in listOf(15, 60, 120, 360, null, 30)) {
            vm.setInterval(preset)
            assertEquals(preset, stored(c).intervalMinutes)
            assertEquals(preset, vm.state.value.check.intervalMinutes)
        }
        vm.setInterval(45) // not a preset: ignored
        vm.setInterval(5)
        assertEquals(30, stored(c).intervalMinutes)
    }

    @Test
    fun wifi_only_uses_unmetered_constraint_via_scheduler() {
        val c = container()
        val vm = vm(c)

        vm.setWifiOnly(true)
        assertTrue(stored(c).wifiOnly)
        assertEquals(NetworkType.UNMETERED, only().constraints.requiredNetworkType)
        assertTrue(only().constraints.requiresBatteryNotLow())

        vm.setWifiOnly(false)
        assertFalse(stored(c).wifiOnly)
        assertEquals(NetworkType.CONNECTED, only().constraints.requiredNetworkType)
        assertEquals(listOf("apply:30", "apply:30"), recording.calls)
    }

    @Test
    fun quiet_hours_wrap_midnight_and_start_equals_end_is_handled() {
        val c = container()
        val vm = vm(c)
        val night = LocalTimeRange(LocalTime.of(22, 0), LocalTime.of(7, 0))

        assertTrue(vm.setQuietHours(night))
        assertEquals(night, stored(c).quietHours)
        assertTrue(LocalTime.of(23, 30) in night)
        assertTrue(LocalTime.of(6, 59) in night)
        assertFalse(LocalTime.of(7, 0) in night)
        assertFalse(LocalTime.of(12, 0) in night)
        vm.setNightStretch(true)
        assertTrue(stored(c).quietNightStretch)

        // start == end is refused (it would be an empty window): nothing stored, a message is shown.
        assertFalse(vm.setQuietHours(LocalTimeRange(LocalTime.of(8, 0), LocalTime.of(8, 0))))
        assertEquals(night, stored(c).quietHours)
        assertTrue(vm.state.value.quietHoursRejected)

        // A valid change clears the message.
        val evening = LocalTimeRange(LocalTime.of(21, 30), LocalTime.of(6, 15))
        assertTrue(vm.setQuietHours(evening))
        assertEquals(evening, stored(c).quietHours)
        assertFalse(vm.state.value.quietHoursRejected)

        // Turning quiet hours off also turns the night stretch off (it only works inside quiet hours).
        assertTrue(vm.setQuietHours(null))
        assertNull(stored(c).quietHours)
        assertFalse(stored(c).quietNightStretch)
    }

    @Test
    fun night_stretch_is_ignored_without_quiet_hours() {
        val c = container()
        val vm = vm(c)
        vm.setNightStretch(true)
        assertFalse(stored(c).quietNightStretch)
        assertTrue("nothing changed, nothing applied", recording.calls.isEmpty())
    }

    @Test
    fun settings_are_persisted_while_unpaired_but_not_scheduled() {
        val unpaired = container(FakeSettingsStore(), FakeTokenStore())
        val vm = vm(unpaired)
        vm.setInterval(60)
        vm.setWifiOnly(true)
        assertEquals(60, stored(unpaired).intervalMinutes)
        assertTrue(stored(unpaired).wifiOnly)
        assertTrue("not scheduled while unpaired", recording.calls.isEmpty())
        assertTrue(active().isEmpty())

        val revoked = container(FakeSettingsStore(URL, needsRepair = true))
        val vm2 = vm(revoked)
        vm2.setInterval(120)
        assertEquals(120, stored(revoked).intervalMinutes)
        assertTrue("not scheduled while a re-pair is needed", recording.calls.isEmpty())
        assertTrue(active().isEmpty())
        assertTrue(vm2.state.value.needsRepair)
        assertTrue(vm2.state.value.checksPaused)
    }

    // ------------------------------------------------------------------ unpair

    @Test
    fun unpair_needs_the_confirmation_step() {
        var calls = 0
        val c = container()
        val vm = SettingsViewModel(c.settingsStore, c.authState, RecordingScheduler(), c::mayScheduleChecks, { calls++; true }, { STATUS_ON }, dispatcher)
        vm.unpair()
        assertEquals("unpair() without the confirmation dialog does nothing", 0, calls)
        vm.requestUnpair()
        vm.dismissUnpair()
        assertFalse(vm.state.value.confirmingUnpair)
        vm.unpair()
        assertEquals(0, calls)
        vm.requestUnpair()
        vm.unpair()
        awaitUnpaired(vm)
        assertEquals(1, calls)
    }

    @Test
    fun unpair_clears_room_rows_sync_state_and_image_caches() {
        val c = container()
        seedRoom(c)
        seedImageCaches()

        val complete = runBlocking { c.unpair() }

        assertTrue(complete)
        runBlocking {
            assertTrue(c.database.updateDao().allById().isEmpty())
            assertNull(c.database.updateDao().syncState())
        }
        assertImageCachesEmpty()
    }

    @Test
    fun unpair_continues_after_a_failing_step() {
        val fake = FakeTokenStore().apply { token = TOKEN }
        val failingTokens = object : TokenStore by fake {
            override fun clear() = throw IllegalStateException("keystore unavailable")
        }
        val settings = FakeSettingsStore(URL, lastSuccessfulCheckAt = 9L)
        val failingSettings = object : SettingsStore by settings {
            override suspend fun clearAll() = throw IOException("disk full")
        }
        val c = container(failingSettings, failingTokens)
        runBlocking { c.scheduleChecksIfPaired() }
        only()
        seedRoom(c)
        seedImageCaches()
        c.authState.markUnauthorized()

        val complete = runBlocking { c.unpair() } // must not throw

        assertFalse("reports that a step failed", complete)
        assertNull("the credentials holder is never left set", c.credentials.get())
        // Fix round 1 (I3): the in-memory caches are emptied even though the Keystore and DataStore calls threw.
        assertNull("in-memory token cleared despite the Keystore failure", c.tokenStore.cachedToken)
        assertNull(c.tokenStore.load())
        assertNull("in-memory server URL cleared despite the DataStore failure", c.settingsStore.cachedServerUrl)
        assertTrue("work cancelled", active().isEmpty())
        runBlocking {
            assertTrue("rows removed after the failing steps", c.database.updateDao().allById().isEmpty())
            assertNull(c.database.updateDao().syncState())
        }
        assertFalse("auth state cleared", c.authState.needsRepair.value)
        assertImageCachesEmpty()
    }

    // ------------------------------------------------------------------ fix round 1 (I1, I2)

    @Test
    fun pairing_success_bumps_the_generation() {
        val c = container()
        val before = c.generation.current()
        runBlocking { c.scheduleAfterPairing() }
        assertTrue("a pairing success starts a new generation", c.generation.current() > before)
        val afterPairing = c.generation.current()
        runBlocking { c.unpair() }
        assertTrue("an unpair starts a new generation", c.generation.current() > afterPairing)
    }

    @Test
    fun unpair_order_awaits_work_cancellation_before_clearing_settings() {
        val events = mutableListOf<String>()
        lateinit var c: AppContainer
        val scheduler = object : CheckScheduler {
            override fun apply(settings: CheckSettings) {
                events += "apply"
            }

            override fun cancel() {
                events += "cancel (not awaited)"
            }

            override suspend fun cancelAndAwait() {
                events += "cancelAndAwait:start generation=${c.generation.current()} holder=${c.credentials.get() != null}"
                yield() // the cancel operation completes asynchronously
                events += "cancelAndAwait:done"
            }
        }
        val tokens = FakeTokenStore().apply { token = TOKEN }
        val recordingTokens = object : TokenStore by tokens {
            override fun clear() {
                events += "token.clear rows=${runBlocking { c.database.updateDao().allById().size }}"
                tokens.clear()
            }
        }
        val settings = FakeSettingsStore(URL, lastSuccessfulCheckAt = 3L)
        val recordingSettings = object : SettingsStore by settings {
            override suspend fun clearAll() {
                events += "settings.clearAll"
                settings.clearAll()
            }
        }
        val notifier = FakeNotifier().apply { onCancelAll = { events += "notifier.cancelAll" } }
        c = container(recordingSettings, recordingTokens, notifier, scheduler)
        seedRoom(c)
        val start = c.generation.current()

        assertTrue(runBlocking { c.unpair() })

        assertEquals(
            listOf(
                "cancelAndAwait:start generation=${start + 1} holder=false", // generation bumped, holder cleared first
                "cancelAndAwait:done",
                "token.clear rows=0", // the cache (rows, cursor) went before the token
                "settings.clearAll",
                "notifier.cancelAll",
            ),
            events,
        )
        assertFalse(c.authState.needsRepair.value)
    }

    @Test
    fun unpair_cancels_posted_notifications() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val c = container() // the real LocalNotifier
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(c.notifier.notifyUpdates(listOf(entity(1), entity(2))))
        assertTrue(c.notifier.notifyRepairNeeded())
        assertTrue("posted before the unpair", shadowOf(manager).allNotifications.isNotEmpty())

        assertTrue(runBlocking { c.unpair() })

        assertTrue("no notification of the old server left", shadowOf(manager).allNotifications.isEmpty())
    }

    // ------------------------------------------------------------------ notifications

    @Test
    fun notification_states_map_to_banner_variants() {
        val denied33 = NotificationStatus(runtimePermission = true, permissionGranted = false, appNotificationsEnabled = false, updateChannelBlocked = false)
        assertEquals(NotificationUi.PermissionNeeded(canRequest = true), notificationUi(denied33, alreadyRequested = false))
        assertEquals(NotificationUi.PermissionNeeded(canRequest = false), notificationUi(denied33, alreadyRequested = true))
        // Below Android 13 there is no runtime permission: only the app switch and the channels.
        val off32 = NotificationStatus(runtimePermission = false, permissionGranted = true, appNotificationsEnabled = false, updateChannelBlocked = false)
        assertEquals(NotificationUi.TurnedOff, notificationUi(off32, alreadyRequested = false))
        assertEquals(NotificationUi.ChannelBlocked, notificationUi(STATUS_ON.copy(updateChannelBlocked = true), false))
        assertEquals(NotificationUi.On, notificationUi(STATUS_ON, false))
    }

    @Test
    fun notification_button_requests_once_then_opens_settings() {
        val settings = FakeSettingsStore(URL)
        val c = container(settings)
        val denied = STATUS_ON.copy(permissionGranted = false, appNotificationsEnabled = false)
        val vm = vm(c) { denied }
        assertNull("nothing is read before the screen resumes", vm.state.value.notifications)

        vm.refreshNotificationStatus()
        assertEquals(NotificationUi.PermissionNeeded(canRequest = true), vm.state.value.notifications)
        assertFalse("never asks on its own", settings.permissionRequested)

        vm.onNotificationButton()
        assertEquals(SettingsEvent.RequestNotificationPermission, runBlocking { withTimeout(5_000) { vm.events.first() } })
        assertTrue(settings.permissionRequested)

        vm.onNotificationPermissionResult(false)
        assertEquals(NotificationUi.PermissionNeeded(canRequest = false), vm.state.value.notifications)
        vm.onNotificationButton()
        assertEquals(SettingsEvent.OpenNotificationSettings, runBlocking { withTimeout(5_000) { vm.events.first() } })
    }

    private companion object {
        const val URL = "https://manga.example.com/"
        const val TOKEN = "tok_settings_0123456789"
        val STATUS_ON = NotificationStatus(runtimePermission = true, permissionGranted = true, appNotificationsEnabled = true, updateChannelBlocked = false)
    }
}
