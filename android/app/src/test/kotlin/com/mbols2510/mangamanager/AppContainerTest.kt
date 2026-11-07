package com.mbols2510.mangamanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.mbols2510.mangamanager.core.TokenStore
import com.mbols2510.mangamanager.data.CheckSettings
import com.mbols2510.mangamanager.data.FakeSettingsStore
import com.mbols2510.mangamanager.data.SettingsStore
import com.mbols2510.mangamanager.data.api.NotPairedException
import com.mbols2510.mangamanager.data.repo.CheckResult
import com.mbols2510.mangamanager.ui.pairing.FakeTokenStore
import com.mbols2510.mangamanager.work.Scheduler
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Storage whose reads never finish: anything that waited for it would hang. */
private class HangingSettingsStore : SettingsStore {
    override val serverUrl: Flow<String?> = emptyFlow()
    override suspend fun serverUrl(): String? = awaitCancellation()
    override suspend fun setServerUrl(url: String?) = awaitCancellation()
    override val checkSettings: Flow<CheckSettings> = emptyFlow()
    override suspend fun checkSettings(): CheckSettings = awaitCancellation()
    override suspend fun setCheckSettings(settings: CheckSettings) = awaitCancellation()
    override val lastSuccessfulCheckAt: Flow<Long?> = emptyFlow()
    override suspend fun lastSuccessfulCheckAt(): Long? = awaitCancellation()
    override suspend fun setLastSuccessfulCheckAt(epochMillis: Long?) = awaitCancellation()
    override val needsRepair: Flow<Boolean> = emptyFlow()
    override suspend fun needsRepair(): Boolean = awaitCancellation()
    override suspend fun setNeedsRepair(value: Boolean) = awaitCancellation()
    override suspend fun clearAll() = awaitCancellation()
}

/** A Keystore that is stuck decrypting until [release] is called. */
private class BlockedTokenStore : TokenStore {
    val release = CountDownLatch(1)
    val entered = CountDownLatch(1)
    override fun save(token: String) = Unit
    override fun load(): String? {
        entered.countDown()
        release.await()
        return null
    }
    override fun clear() = Unit
}

@RunWith(RobolectricTestRunner::class)
class AppContainerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val servers = mutableListOf<MockWebServer>()
    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    @Before
    fun setUp() {
        // unpair() and the start-up scheduling reach WorkManager; use the test instance.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    private fun activeChecks() = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(Scheduler.UNIQUE_WORK_NAME).get()
        .filterNot { it.state.isFinished }

    @Test
    fun warm_up_restores_the_persisted_repair_flag() {
        val container = AppContainer(context, FakeSettingsStore(needsRepair = true), FakeTokenStore())
        assertFalse(container.authState.needsRepair.value)
        runBlocking { container.warmUp() }
        assertTrue("a 401 seen by the worker in an earlier process survives", container.authState.needsRepair.value)
    }

    @Test
    fun app_start_schedules_checks_only_when_paired_and_unpair_cancels_them() {
        val tokens = FakeTokenStore().apply { token = "tok_start_0123456789" }
        val container = AppContainer(context, FakeSettingsStore("https://manga.example.com/"), tokens)
        runBlocking {
            container.warmUp()
            container.scheduleChecksIfPaired()
        }
        assertEquals(1, activeChecks().size)
        runBlocking { container.unpair() }
        assertTrue(activeChecks().isEmpty())

        val unpaired = AppContainer(context, FakeSettingsStore(), FakeTokenStore())
        runBlocking {
            unpaired.warmUp()
            unpaired.scheduleChecksIfPaired()
        }
        assertTrue(activeChecks().isEmpty())

        val revoked = AppContainer(
            context,
            FakeSettingsStore("https://manga.example.com/", needsRepair = true),
            FakeTokenStore().apply { token = "tok_start_0123456789" },
        )
        runBlocking {
            revoked.warmUp()
            revoked.scheduleChecksIfPaired()
        }
        assertTrue("no checks while waiting for a re-pair", activeChecks().isEmpty())
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
        executors.forEach { it.shutdownNow() }
    }

    private fun server(): MockWebServer =
        MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }.also { servers += it }

    private fun <T> onOtherThread(block: () -> T): java.util.concurrent.Future<T> {
        val executor = Executors.newSingleThreadExecutor().also { executors += it }
        return executor.submit(Callable { block() })
    }

    /** Runs [block] on a plain thread and fails (instead of hanging the build) if it takes longer than 5 s. */
    private fun <T> withinFiveSeconds(block: () -> T): T = onOtherThread(block).get(5, TimeUnit.SECONDS)

    private fun OkHttpClient.get(url: okhttp3.HttpUrl) {
        newCall(Request.Builder().url(url).build()).execute().close()
    }

    private fun pair(container: AppContainer, server: MockWebServer, token: String) = runBlocking {
        container.settingsStore.setServerUrl(server.url("/").toString())
        container.tokenStore.save(token)
        container.credentials.set(server.url("/"), token) // what a successful pairing publishes
    }

    @Test
    fun client_and_repository_never_block_on_unloaded_settings() {
        val tokens = FakeTokenStore().apply { token = "t" }
        val container = AppContainer(context, HangingSettingsStore(), tokens)

        val result = withinFiveSeconds {
            val client = container.httpClient()
            val refused = runCatching { client.get(okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(1).build()) }
            Triple(client, refused.exceptionOrNull(), runBlocking { container.updatesRepository.checkForChanges() })
        }

        assertNotNull("the client exists before anything is loaded", result.first)
        assertTrue("a request through it fails fast: ${result.second}", result.second is NotPairedException)
        assertEquals("repository answers Offline instead of hanging", CheckResult.Offline, result.third)
        assertFalse("Offline does not flag the device for re-pairing", container.authState.needsRepair.value)
        assertEquals("the token store was not touched", 0, tokens.loads)
    }

    @Test
    fun shared_client_is_never_null_and_same_instance() {
        val container = AppContainer(context, FakeSettingsStore(), FakeTokenStore())
        val (first, second) = withinFiveSeconds { container.httpClient() to container.httpClient() }
        assertNotNull(first)
        assertSame(first, second)

        pair(container, server(), "tok_first_0123456789")
        assertSame("pairing does not rebuild the client", first, container.httpClient())
        runBlocking { container.unpair() }
        assertSame("unpairing does not rebuild the client", first, container.httpClient())
    }

    @Test
    fun shared_client_is_built_from_the_cache_without_reading_the_token() {
        val tokens = FakeTokenStore()
        val container = AppContainer(context, FakeSettingsStore("https://manga.example.com/"), tokens)
        runBlocking { container.settingsStore.warmUp() }

        val (first, second) = withinFiveSeconds { container.httpClient() to container.httpClient() }

        assertSame("one shared client", first, second)
        assertEquals("building the client reads no token (Keystore)", 0, tokens.loads)
    }

    @Test
    fun a_stuck_keystore_read_does_not_block_the_client() {
        val keystore = BlockedTokenStore()
        val container = AppContainer(context, FakeSettingsStore(), keystore)
        val warming = onOtherThread { runBlocking { container.warmUp() } }
        assertTrue("warm-up is inside the Keystore read", keystore.entered.await(5, TimeUnit.SECONDS))

        // Another thread (the main thread in production) gets the client immediately: no shared monitor.
        val client = withinFiveSeconds { container.httpClient() }
        assertNotNull(client)
        val refused = withinFiveSeconds { runCatching { client.get(server().url("/")) }.exceptionOrNull() }
        assertTrue("cold cache: $refused", refused is NotPairedException)
        assertFalse("warm-up is still stuck", warming.isDone)

        keystore.release.countDown()
        warming.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun warm_up_publishes_the_stored_pairing_but_never_over_a_newer_one() {
        val a = server()
        val tokens = FakeTokenStore().apply { token = "tok_stored_0123456789" }
        val container = AppContainer(context, FakeSettingsStore(a.url("/").toString()), tokens)
        runBlocking { container.warmUp() }
        assertEquals("tok_stored_0123456789", container.credentials.get()!!.token)
        assertEquals(a.url("/"), container.credentials.get()!!.base)

        // A pairing (or unpairing) that ran before a late warm-up wins.
        val other = AppContainer(context, FakeSettingsStore(a.url("/").toString()), tokens)
        other.credentials.clear()
        runBlocking { other.warmUp() }
        assertNull(other.credentials.get())
    }

    @Test
    fun writes_update_the_cache_and_unpairing_clears_it() {
        val container = AppContainer(context, FakeSettingsStore(), FakeTokenStore())
        val store = container.settingsStore
        assertFalse(store.isLoaded)
        runBlocking { store.setServerUrl("https://manga.example.com/") }
        assertTrue(store.isLoaded)
        assertEquals("https://manga.example.com/", store.requireCachedServerUrl())

        runBlocking { store.clearAll() }
        assertNull(store.requireCachedServerUrl())
    }

    @Test
    fun unpair_clears_the_caches_and_the_next_request_fails_not_paired() {
        val container = AppContainer(context, FakeSettingsStore(), FakeTokenStore())
        val a = server()
        pair(container, a, "tok_first_0123456789")
        a.enqueue(MockResponse().setBody("ok"))
        container.httpClient().get(a.url("/api/status"))
        assertEquals("Bearer tok_first_0123456789", a.takeRequest().getHeader("Authorization"))

        runBlocking { container.unpair() }

        assertNull(container.credentials.get())
        assertNull(container.tokenStore.cachedToken)
        assertNull(container.settingsStore.cachedServerUrl)
        assertNull("no API when unpaired", container.pairingApi())
        val e = assertThrows(IOException::class.java) { container.httpClient().get(a.url("/api/status")) }
        assertTrue("$e", e is NotPairedException)
        assertEquals("only the first request reached the server", 1, a.requestCount)
    }

    @Test
    fun repair_to_a_different_server_url_uses_the_new_server_and_token() = runBlocking {
        val container = AppContainer(context, FakeSettingsStore(), FakeTokenStore())
        val a = server()
        val b = server()
        pair(container, a, "tok_first_0123456789")
        a.enqueue(MockResponse().setBody("""{"cursor":1}""").setHeader("Content-Type", "application/json"))
        assertEquals(1L, container.pairingApi()!!.latest().cursor)
        assertEquals("Bearer tok_first_0123456789", a.takeRequest().getHeader("Authorization"))
        val client = container.httpClient()

        pair(container, b, "tok_second_9876543210")
        b.enqueue(MockResponse().setBody("""{"cursor":2}""").setHeader("Content-Type", "application/json"))
        assertEquals(2L, container.pairingApi()!!.latest().cursor)
        assertEquals("Bearer tok_second_9876543210", b.takeRequest().getHeader("Authorization"))

        assertSame("same client after the re-pair", client, container.httpClient())
        assertThrows(NotPairedException::class.java) { client.get(a.url("/api/status")) }
        assertEquals("the old server got nothing more", 1, a.requestCount)
    }

    @Test
    fun coil_style_image_requests_are_gated_to_the_paired_origin() {
        val container = AppContainer(context, FakeSettingsStore(), FakeTokenStore())
        val a = server()
        val other = server()
        pair(container, a, "tok_first_0123456789")
        a.enqueue(MockResponse().setBody("png"))

        container.httpClient().get(a.url("/thumb/5.png"))
        assertEquals("Bearer tok_first_0123456789", a.takeRequest().getHeader("Authorization"))

        assertThrows(NotPairedException::class.java) { container.httpClient().get(other.url("/thumb/5.png")) }
        assertEquals("rejected before any network event", 0, other.requestCount)
    }
}
