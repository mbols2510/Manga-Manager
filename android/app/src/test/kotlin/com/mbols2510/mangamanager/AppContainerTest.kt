package com.mbols2510.mangamanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mbols2510.mangamanager.data.FakeSettingsStore
import com.mbols2510.mangamanager.data.SettingsStore
import com.mbols2510.mangamanager.data.repo.CheckResult
import com.mbols2510.mangamanager.ui.pairing.FakeTokenStore
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Storage whose reads never finish: anything that waited for it would hang. */
private class HangingSettingsStore : SettingsStore {
    override val serverUrl: Flow<String?> = emptyFlow()
    override suspend fun serverUrl(): String? = awaitCancellation()
    override suspend fun setServerUrl(url: String?) = awaitCancellation()
    override suspend fun clearAll() = awaitCancellation()
}

@RunWith(RobolectricTestRunner::class)
class AppContainerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Runs [block] on a plain thread and fails (instead of hanging the build) if it takes longer than 5 s. */
    private fun <T> withinFiveSeconds(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try {
            return executor.submit(Callable { block() }).get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun client_and_repository_never_block_on_unloaded_settings() {
        val tokens = FakeTokenStore().apply { token = "t" }
        val container = AppContainer(context, HangingSettingsStore(), tokens)

        val result = withinFiveSeconds {
            Triple(
                container.httpClient(),
                runBlocking { container.updatesRepository.checkForChanges() },
                container.settingsStore.isLoaded,
            )
        }

        assertNull("no client before the settings cache is filled", result.first)
        assertEquals("repository answers Offline instead of hanging", CheckResult.Offline, result.second)
        assertFalse(result.third)
        assertFalse("Offline does not flag the device for re-pairing", container.authState.needsRepair.value)
        assertEquals("the token store was not touched", 0, tokens.loads)
    }

    @Test
    fun shared_client_is_built_from_the_cache_without_reading_the_token() {
        val tokens = FakeTokenStore()
        val container = AppContainer(context, FakeSettingsStore("https://manga.example.com/"), tokens)
        runBlocking { container.settingsStore.warmUp() }

        val (first, second) = withinFiveSeconds { container.httpClient() to container.httpClient() }

        assertNotNull(first)
        assertSame("one shared client", first, second)
        assertEquals("building the client reads no token (Keystore)", 0, tokens.loads)
    }

    @Test
    fun writes_update_the_cache_and_unpairing_clears_it() {
        val container = AppContainer(context, FakeSettingsStore(), FakeTokenStore())
        val store = container.settingsStore
        assertFalse(store.isLoaded)
        runBlocking { store.setServerUrl("https://manga.example.com/") }
        assertTrue(store.isLoaded)
        assertEquals("https://manga.example.com/", store.requireCachedServerUrl())
        assertNotNull(container.httpClient())

        runBlocking { store.clearAll() }
        assertNull(store.requireCachedServerUrl())
        assertNull("not paired: no client", container.httpClient())
    }
}
