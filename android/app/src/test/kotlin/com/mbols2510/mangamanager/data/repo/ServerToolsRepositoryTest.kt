package com.mbols2510.mangamanager.data.repo

import com.mbols2510.mangamanager.data.api.MangaApi
import com.mbols2510.mangamanager.data.api.MangaApiFactory
import com.mbols2510.mangamanager.data.api.StatusDto
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The real HTTP stack: MockWebServer + the production OkHttp/Retrofit client + [DefaultServerToolsRepository]. */
@RunWith(RobolectricTestRunner::class)
class ServerToolsRepositoryTest {
    private lateinit var server: MockWebServer
    private val auth = AuthState()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private val base: HttpUrl
        get() = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.port).encodedPath("/").build()

    /** The production client, optionally with a short read timeout (the real one is 15 s). */
    private fun api(readTimeoutMs: Long? = null): MangaApi {
        if (readTimeoutMs == null) return MangaApiFactory.createWith(base, { "tok" }, auth::markUnauthorized)
        val client = MangaApiFactory.client(base, { "tok" }, auth::markUnauthorized).newBuilder()
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return MangaApiFactory.retrofit(base, client).create(MangaApi::class.java)
    }

    private fun repo(api: MangaApi? = api()) = DefaultServerToolsRepository({ api }, auth)

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun run_statuses_map_to_distinct_outcomes_over_http() = runBlocking {
        val r = repo()
        server.enqueue(json(202, """{"started": true, "message": "Run started"}"""))
        server.enqueue(json(409, """{"started": false, "message": "A run is already in progress"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "text/html").setBody("<h1>Forbidden</h1>"))
        server.enqueue(json(500, """{"error": "boom"}"""))

        assertEquals(ToolResult.Success(RunOutcome.Started), r.runNow())
        assertEquals(ToolResult.Success(RunOutcome.AlreadyRunning), r.runNow())
        assertEquals(ToolResult.Success(RunOutcome.Disabled), r.runNow())
        assertEquals(ToolResult.ServerError(500, "boom"), r.runNow())
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/run", sent.path)
        assertFalse(auth.needsRepair.value)
    }

    @Test
    fun remediate_posts_keep_file_id_and_parses_the_real_result() = runBlocking {
        server.enqueue(json(200, fixture("remediate_result.json")))
        val result = repo().remediate(12, 501, 9001)
        val moved = (result as ToolResult.Success).value.moved
        assertEquals(listOf(501L), moved.map { it.chapterId })
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/double-chapters/series/12/chapters/501/remediate", sent.path)
        assertEquals("""{"keepFileId":9001}""", sent.body.readUtf8())
        assertEquals("Bearer tok", sent.getHeader("Authorization"))
    }

    @Test
    fun remediate_read_timeout_is_maybe_applied_but_a_read_call_timeout_is_offline() = runBlocking {
        val r = repo(api(readTimeoutMs = 200))
        server.enqueue(json(200, fixture("remediate_result.json")).setHeadersDelay(1500, TimeUnit.MILLISECONDS))
        assertEquals(ToolResult.MaybeApplied, r.remediate(12, 501, 9001))

        server.enqueue(json(200, "[]").setHeadersDelay(1500, TimeUnit.MILLISECONDS))
        assertEquals(ToolResult.Offline, r.doubleSeries())

        server.enqueue(json(200, fixture("double_trash.json")).setHeadersDelay(1500, TimeUnit.MILLISECONDS))
        assertEquals(ToolResult.MaybeApplied, r.restoreTrash("legacy-1"))
    }

    @Test
    fun refused_connection_on_a_changing_call_is_offline_not_maybe_applied() = runBlocking {
        val r = repo()
        server.shutdown()
        assertEquals(ToolResult.Offline, r.remediate(1, 2, 3))
        assertEquals(ToolResult.Offline, r.restoreTrash("x"))
        assertEquals(ToolResult.Offline, r.runNow())
        assertEquals(ToolResult.Offline, r.status())
    }

    @Test
    fun every_call_maps_an_own_401_to_unauthorized() = runBlocking {
        val r = repo()
        val calls = listOf<suspend () -> ToolResult<*>>(
            { r.doubleSeries() },
            { r.doubleSeriesDetail(1) },
            { r.remediate(1, 2, 3) },
            { r.trash() },
            { r.restoreTrash("x") },
            { r.status() },
            { r.health() },
            { r.runNow() },
        )
        calls.forEachIndexed { i, call ->
            auth.clear()
            server.enqueue(json(401, """{"error": "unauthorized"}"""))
            assertEquals("call $i", ToolResult.Unauthorized, call())
            assertTrue("call $i sets AuthState", auth.needsRepair.value)
        }
    }

    @Test
    fun server_error_text_is_read_and_html_is_ignored() = runBlocking {
        val r = repo()
        server.enqueue(json(400, """{"error": "A file already exists at the original location"}"""))
        assertEquals(ToolResult.ServerError(400, "A file already exists at the original location"), r.restoreTrash("x"))

        server.enqueue(json(404, """{"error": "Series has no duplicate chapters (or was not scanned yet)"}"""))
        assertEquals(
            ToolResult.ServerError(404, "Series has no duplicate chapters (or was not scanned yet)"),
            r.doubleSeriesDetail(5),
        )

        server.enqueue(MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<h1>Bad gateway</h1>"))
        assertEquals(ToolResult.ServerError(502, null), r.status())

        server.enqueue(json(400, """{"error": "${"x".repeat(1000)}"}"""))
        val long = r.remediate(1, 2, 3) as ToolResult.ServerError
        assertEquals(DefaultServerToolsRepository.MAX_ERROR_CHARS, long.message!!.length)
    }

    @Test
    fun not_paired_is_unauthorized_and_a_cold_cache_is_offline() = runBlocking {
        assertEquals(ToolResult.Unauthorized, DefaultServerToolsRepository({ null }, auth).remediate(1, 2, 3))
        assertTrue(auth.needsRepair.value)

        auth.clear()
        val cold = DefaultServerToolsRepository({ error("Pairing settings are not loaded yet") }, auth)
        assertEquals("nothing was sent", ToolResult.Offline, cold.remediate(1, 2, 3))
        val failedWarmUp = DefaultServerToolsRepository({ api() }, auth, ensureReady = { error("keystore") })
        assertEquals(ToolResult.Offline, failedWarmUp.runNow())
        assertFalse(auth.needsRepair.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun calls_run_on_the_injected_dispatcher() {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "tools-io-test") }
        try {
            val fake = ToolsApi()
            var thread = ""
            fake.statusHandler = {
                thread = Thread.currentThread().name
                StatusDto()
            }
            val result = runBlocking {
                DefaultServerToolsRepository({ fake }, auth, io = executor.asCoroutineDispatcher()).status()
            }
            assertEquals(ToolResult.Success(StatusDto()), result)
            // Coroutine debug mode (on under -ea) appends " @coroutine#N" to the thread name.
            assertTrue("ran on $thread", thread.startsWith("tools-io-test"))
        } finally {
            executor.shutdownNow()
        }
    }
}
