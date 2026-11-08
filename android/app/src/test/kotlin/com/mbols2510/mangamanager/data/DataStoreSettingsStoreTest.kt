package com.mbols2510.mangamanager.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun store() = DataStoreSettingsStore(
        PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") },
    )

    @Test
    fun server_url_round_trips_and_null_removes_it() = runBlocking {
        val store = store()
        assertNull(store.serverUrl())
        store.setServerUrl("https://manga.example.com/")
        assertEquals("https://manga.example.com/", store.serverUrl())
        assertEquals("https://manga.example.com/", store.serverUrl.first())
        store.setServerUrl(null)
        assertNull(store.serverUrl())
    }

    @Test
    fun notification_prompt_flag_survives_clear_all() = runBlocking {
        val store = store()
        assertFalse(store.notificationPermissionRequested())
        store.setNotificationPermissionRequested()
        store.setServerUrl("https://manga.example.com/")
        store.clearAll()
        assertTrue("unpairing does not re-arm the system prompt", store.notificationPermissionRequested())
        assertNull(store.serverUrl())
    }

    @Test
    fun clear_all_removes_everything() = runBlocking {
        val store = store()
        store.setServerUrl("http://192.168.1.5:5000/")
        store.setCheckSettings(CheckSettings(60, wifiOnly = true, quietNightStretch = true, quietHours = null))
        store.setLastSuccessfulCheckAt(1234L)
        store.setNeedsRepair(true)
        store.clearAll()
        assertNull(store.serverUrl.first())
        assertEquals(CheckSettings.DEFAULT, store.checkSettings())
        assertNull(store.lastSuccessfulCheckAt())
        assertFalse(store.needsRepair())
    }

    @Test
    fun check_settings_default_to_30_minutes_and_round_trip() = runBlocking {
        val store = store()
        assertEquals(CheckSettings(30, wifiOnly = false, quietNightStretch = false, quietHours = null), store.checkSettings())

        val quiet = LocalTimeRange(LocalTime.of(22, 30), LocalTime.of(7, 15))
        val custom = CheckSettings(120, wifiOnly = true, quietNightStretch = true, quietHours = quiet)
        store.setCheckSettings(custom)
        assertEquals(custom, store.checkSettings())
        assertEquals(custom, store.checkSettings.first())

        val off = custom.copy(intervalMinutes = null, quietHours = null)
        store.setCheckSettings(off)
        assertEquals("null interval (off) survives a round trip", off, store.checkSettings())

        store.setCheckSettings(custom.copy(intervalMinutes = 5))
        assertEquals("stored values are clamped", 15, store.checkSettings().intervalMinutes)
    }

    @Test
    fun last_successful_check_and_repair_flag_round_trip() = runBlocking {
        val store = store()
        assertNull(store.lastSuccessfulCheckAt())
        assertFalse(store.needsRepair())
        store.setLastSuccessfulCheckAt(1_700_000_000_000L)
        store.setNeedsRepair(true)
        assertEquals(1_700_000_000_000L, store.lastSuccessfulCheckAt.first())
        assertTrue(store.needsRepair.first())
        store.setNeedsRepair(false)
        store.setLastSuccessfulCheckAt(null)
        assertFalse(store.needsRepair())
        assertNull(store.lastSuccessfulCheckAt())
    }

    @Test
    fun quiet_hours_may_wrap_midnight() {
        val night = LocalTimeRange(LocalTime.of(22, 0), LocalTime.of(7, 0))
        assertTrue(LocalTime.of(23, 59) in night)
        assertTrue(LocalTime.of(0, 0) in night)
        assertTrue(LocalTime.of(6, 59) in night)
        assertFalse(LocalTime.of(7, 0) in night)
        assertFalse(LocalTime.of(21, 59) in night)
        val day = LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))
        assertTrue(LocalTime.of(9, 0) in day)
        assertFalse(LocalTime.of(17, 0) in day)
        assertFalse(LocalTime.of(3, 0) in LocalTimeRange(LocalTime.of(3, 0), LocalTime.of(3, 0)))
    }
}
