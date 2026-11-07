package com.mbols2510.mangamanager.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun clear_all_removes_everything() = runBlocking {
        val store = store()
        store.setServerUrl("http://192.168.1.5:5000/")
        store.clearAll()
        assertNull(store.serverUrl.first())
    }
}
