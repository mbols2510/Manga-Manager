package com.mbols2510.mangamanager.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mbols2510.mangamanager.core.TokenStore
import com.mbols2510.mangamanager.data.api.ChangesDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesDto
import com.mbols2510.mangamanager.data.api.DoubleSeriesSummaryDto
import com.mbols2510.mangamanager.data.api.DoublesStatusDto
import com.mbols2510.mangamanager.data.api.HealthDto
import com.mbols2510.mangamanager.data.api.KavitaScanRequest
import com.mbols2510.mangamanager.data.api.KavitaScanResultDto
import com.mbols2510.mangamanager.data.api.LatestDto
import com.mbols2510.mangamanager.data.api.MangaApi
import com.mbols2510.mangamanager.data.api.RemediateRequest
import com.mbols2510.mangamanager.data.api.RemediateResultDto
import com.mbols2510.mangamanager.data.api.RunDto
import com.mbols2510.mangamanager.data.api.StatusDto
import com.mbols2510.mangamanager.data.api.TrashEntryDto
import com.mbols2510.mangamanager.data.api.UpdateDto
import com.mbols2510.mangamanager.data.api.UpdatesPageDto
import com.mbols2510.mangamanager.data.api.MangaApiFactory
import com.mbols2510.mangamanager.data.api.PairedCredentialsHolder
import com.mbols2510.mangamanager.data.db.AppDatabase
import com.mbols2510.mangamanager.data.db.RoomUpdatesCache
import com.mbols2510.mangamanager.data.db.SyncState
import com.mbols2510.mangamanager.data.db.UpdateEntity
import com.mbols2510.mangamanager.data.db.UpdatesCache
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real stack: MockWebServer + the production OkHttp/Retrofit client + in-memory Room (Robolectric).
 * The Robolectric SDK level comes from src/test/resources/robolectric.properties.
 */
@RunWith(RobolectricTestRunner::class)
class UpdatesRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var auth: AuthState
    private var now = 1_000L

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        auth = AuthState()
    }

    @After
    fun tearDown() {
        db.close()
        runCatching { server.shutdown() }
    }

    // ---------------------------------------------------------------- helpers

    private val base: HttpUrl
        get() = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.port).encodedPath("/").build()

    private fun api(maxBodyBytes: Long = MangaApiFactory.MAX_BODY_BYTES): MangaApi =
        MangaApiFactory.createWith(base, { "tok" }, auth::markUnauthorized, maxBodyBytes = maxBodyBytes)

    /** Counts every write that reaches the cache. */
    private class CountingCache(private val d: UpdatesCache) : UpdatesCache by d {
        var writes = 0
        override suspend fun setSyncState(state: SyncState) { writes++; d.setSyncState(state) }
        override suspend fun applyChanges(items: List<UpdateEntity>, state: SyncState): List<UpdateEntity> {
            writes++
            return d.applyChanges(items, state)
        }
        override suspend fun markNotified(ids: Collection<Long>) { writes++; d.markNotified(ids) }
    }

    private fun repo(
        cache: UpdatesCache = RoomUpdatesCache(db),
        api: MangaApi? = api(),
        maxPages: Int = DefaultUpdatesRepository.MAX_PAGES,
    ) = DefaultUpdatesRepository({ api }, cache, auth, { now }, DefaultUpdatesRepository.PAGE_SIZE, maxPages)

    private fun item(id: Long, title: String = "Title $id", chapters: String = """["Ch. $id"]""", extra: String = "") =
        """{"id":$id,"title":${q(title)},"category":"Manga","is_new":${id % 2 == 0L},"chapters":$chapters,
           "created_at":"2026-11-0${1 + (id % 9)}T10:00:00+00:00","thumbnail":"thumb/$id.png",
           "kavita_url":"https://kavita.example/s/$id","sensitive":false$extra}"""

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun page(ids: List<Long>, next: Long, more: Boolean, etag: String? = "\"e$next\"") =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").apply {
            if (etag != null) setHeader("ETag", etag)
        }.setBody("""{"items":[${ids.joinToString(",") { item(it) }}],"next_cursor":$next,"has_more":$more}""")

    private fun latest(cursor: Long) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"cursor":$cursor}""")

    private fun seed(cursor: Long, etag: String? = null, vararg ids: Long) = runBlocking {
        val cache = RoomUpdatesCache(db)
        if (ids.isEmpty()) {
            cache.setSyncState(SyncState(cursor, etag, 1L))
        } else {
            cache.applyChanges(ids.map { entity(it) }, SyncState(cursor, etag, 1L))
        }
    }

    private fun entity(id: Long) = UpdateEntity(id, "Seed $id", "Manga", false, listOf("c$id"), "2026-10-01T00:00:00+00:00", null, null, false)

    private suspend fun snapshot(): Pair<List<UpdateEntity>, SyncState?> = db.updateDao().allById() to db.updateDao().syncState()

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    // ---------------------------------------------------------------- brief tests

    @Test
    fun `304_makes_no_db_write_and_returns_NoChange`() = runBlocking {
        seed(5, "\"etag-5\"", 4, 5)
        val before = snapshot()
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"etag-5\""))
        val counting = CountingCache(RoomUpdatesCache(db))

        assertEquals(CheckResult.NoChange, repo(cache = counting).checkForChanges())

        assertEquals("no write may reach the cache on 304", 0, counting.writes)
        assertEquals("tables unchanged", before, snapshot())
        val req = take()
        assertEquals("/api/v1/changes?since=5&limit=50", req.path)
        assertEquals("\"etag-5\"", req.getHeader("If-None-Match"))
    }

    @Test
    fun `new_items_upserted_and_cursor_advanced_in_one_transaction`() = runBlocking {
        seed(10, "\"old\"")
        // 1) A failure injected between the upsert and the cursor write rolls everything back.
        server.enqueue(page(listOf(11, 12), next = 12, more = false))
        val failing = RoomUpdatesCache(db) { throw IllegalStateException("injected after upsert") }
        assertEquals(CheckResult.Offline, repo(cache = failing).checkForChanges())
        assertEquals(SyncState(10, "\"old\"", 1L), db.updateDao().syncState())
        assertTrue("no item may persist", db.updateDao().allById().isEmpty())

        // 2) The same answer without the failure: items stored, cursor and ETag advanced together.
        server.enqueue(page(listOf(11, 12), next = 12, more = false, etag = "\"e12\""))
        now = 5_000L
        val result = repo().checkForChanges()
        assertEquals(listOf(11L, 12L), (result as CheckResult.NewItems).items.map { it.id })
        assertEquals(listOf(11L, 12L), db.updateDao().allById().map { it.id })
        assertEquals(SyncState(12, "\"e12\"", 5_000L), db.updateDao().syncState())
        assertEquals("\"old\"", take().getHeader("If-None-Match"))
        assertEquals("\"old\"", take().getHeader("If-None-Match"))
    }

    @Test
    fun `410_resets_baseline_without_returning_new_items`() = runBlocking {
        seed(100, "\"e100\"", 99, 100)
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"cursor too old or reset"}"""))
        server.enqueue(latest(7))
        now = 9_000L

        assertEquals(CheckResult.NoChange, repo().checkForChanges())

        assertEquals(SyncState(7, null, 9_000L), db.updateDao().syncState())
        assertEquals("cached items are kept, none added", listOf(99L, 100L), db.updateDao().allById().map { it.id })
        assertEquals("/api/v1/changes?since=100&limit=50", take().path)
        assertEquals("/api/v1/changes/latest", take().path)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `401_sets_needsRepair_and_returns_Unauthorized`() = runBlocking {
        seed(3)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        assertFalse(auth.needsRepair.value)

        assertEquals(CheckResult.Unauthorized, repo().checkForChanges())
        assertTrue(auth.needsRepair.value)
        assertEquals(SyncState(3, null, 1L), db.updateDao().syncState())

        // Review Focus 4: it stays true, even after a later successful call, until clear().
        server.enqueue(page(emptyList(), next = 3, more = false, etag = null))
        assertEquals(CheckResult.NoChange, repo().checkForChanges())
        assertTrue(auth.needsRepair.value)
        auth.clear()
        assertFalse(auth.needsRepair.value)
    }

    @Test
    fun `io_error_returns_Offline`() = runBlocking {
        seed(3, "\"e3\"", 3)
        val before = snapshot()
        val api = api()
        server.shutdown() // connection refused
        assertEquals(CheckResult.Offline, repo(api = api).checkForChanges())
        assertEquals(before, snapshot())
        assertFalse(auth.needsRepair.value)
    }

    @Test
    fun `duplicate_ids_are_upserted_once`() = runBlocking {
        seed(0)
        // Item 2 is delivered twice across pages; item 3 is already notified locally.
        server.enqueue(page(listOf(1, 2), next = 2, more = true))
        server.enqueue(page(listOf(2, 3), next = 3, more = false))
        val first = repo().checkForChanges() as CheckResult.NewItems
        assertEquals(listOf(1L, 2L, 3L), first.items.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), db.updateDao().allById().map { it.id })
        repo().markNotified(listOf(3L))

        // Later delivery of 3 again (e.g. a re-sent page) plus a new 4: only 4 is new; 3 keeps notified=true.
        server.enqueue(page(listOf(3, 4), next = 4, more = false))
        val second = repo().checkForChanges() as CheckResult.NewItems
        assertEquals(listOf(4L), second.items.map { it.id })
        val rows = db.updateDao().allById()
        assertEquals(listOf(1L, 2L, 3L, 4L), rows.map { it.id })
        assertTrue(rows.single { it.id == 3L }.notified)
        assertFalse(rows.single { it.id == 4L }.notified)

        // Only known ids: NoChange.
        server.enqueue(page(listOf(4), next = 4, more = false))
        assertEquals(CheckResult.NoChange, repo().checkForChanges())
        assertEquals(4, db.updateDao().allById().size)
    }

    // ---------------------------------------------------------------- data flow details

    @Test
    fun fresh_install_baselines_from_latest_and_never_from_zero() = runBlocking {
        server.enqueue(latest(42))
        assertEquals(CheckResult.NoChange, repo().checkForChanges())
        assertEquals(SyncState(42, null, 1_000L), db.updateDao().syncState())
        assertTrue(db.updateDao().allById().isEmpty())
        assertEquals("/api/v1/changes/latest", take().path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun backlog_of_ten_pages_is_paged_in_one_check_and_cursor_ends_at_last_id() = runBlocking {
        seed(0)
        for (p in 0 until 10) {
            val ids = (p * 50L + 1..p * 50L + 50).toList()
            server.enqueue(page(ids, next = ids.last(), more = p < 9))
        }
        val result = repo().checkForChanges() as CheckResult.NewItems
        assertEquals(500, result.items.size)
        assertEquals((1L..500L).toList(), result.items.map { it.id })
        assertEquals(500L, db.updateDao().syncState()!!.cursor)
        assertEquals("\"e500\"", db.updateDao().syncState()!!.etag)
        assertEquals(10, server.requestCount)
        val requests = (0 until 10).map { take() }
        assertEquals("/api/v1/changes?since=450&limit=50", requests.last().path)
        assertTrue("later pages carry no If-None-Match", requests.drop(1).all { it.getHeader("If-None-Match") == null })
    }

    @Test
    fun page_cap_stops_a_server_that_never_ends() = runBlocking {
        seed(0)
        for (p in 0 until 5) server.enqueue(page(listOf(p * 2L + 1, p * 2L + 2), next = p * 2L + 2, more = true))
        val result = repo(maxPages = 3).checkForChanges() as CheckResult.NewItems
        assertEquals((1L..6L).toList(), result.items.map { it.id })
        assertEquals(SyncState(6, null, 1_000L), db.updateDao().syncState())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun empty_items_without_more_returns_NoChange_and_stores_no_items() = runBlocking {
        seed(8, null)
        server.enqueue(page(emptyList(), next = 8, more = false, etag = "\"e8\""))
        val counting = CountingCache(RoomUpdatesCache(db))
        assertEquals(CheckResult.NoChange, repo(cache = counting).checkForChanges())
        assertTrue(db.updateDao().allById().isEmpty())
        // The new ETag is remembered once so the next check can be a 304.
        assertEquals(SyncState(8, "\"e8\"", 1_000L), db.updateDao().syncState())

        server.enqueue(page(emptyList(), next = 8, more = false, etag = "\"e8\""))
        counting.writes = 0
        assertEquals(CheckResult.NoChange, repo(cache = counting).checkForChanges())
        assertEquals("same ETag: no write", 0, counting.writes)
    }

    @Test
    fun malformed_json_returns_Offline() = runBlocking {
        seed(1, "\"e1\"")
        val before = snapshot()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"items":[{"id":"""))
        assertEquals(CheckResult.Offline, repo().checkForChanges())
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("<html>proxy login</html>"))
        assertEquals(CheckResult.Offline, repo().checkForChanges())
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"items":[]}"""))
        assertEquals("missing next_cursor", CheckResult.Offline, repo().checkForChanges())
        assertEquals(before, snapshot())
    }

    @Test
    fun oversized_body_returns_Offline() = runBlocking {
        seed(1)
        val before = snapshot()
        val big = (2L..200L).toList()
        server.enqueue(page(big, next = 200, more = false))
        assertEquals(CheckResult.Offline, repo(api = api(maxBodyBytes = 4_096)).checkForChanges())
        assertEquals(before, snapshot())
    }

    @Test
    fun more_items_than_requested_are_still_stored() = runBlocking {
        seed(0)
        server.enqueue(page((1L..120L).toList(), next = 120, more = false))
        val result = repo().checkForChanges() as CheckResult.NewItems
        assertEquals(120, result.items.size)
        assertEquals(120L, db.updateDao().syncState()!!.cursor)
    }

    @Test
    fun unknown_fields_and_awkward_chapters_round_trip_through_room() = runBlocking {
        seed(0)
        val chapters = """["Ch. 1, \"part\" a", "a;b|c", "", "日本語 🎉", "[x]"]"""
        val extra = ""","suwayomi_id":5,"nested":{"a":[1,{"b":null}]}"""
        val one = item(1, title = "<b>Café</b> \"quoted\"", chapters = chapters, extra = extra)
        val body = """{"items":[$one],"next_cursor":1,"has_more":false,"new_top_level":true}"""
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body))
        val stored = (repo().checkForChanges() as CheckResult.NewItems).items.single()
        assertEquals("<b>Café</b> \"quoted\"", stored.title)
        val row = db.updateDao().allById().single()
        assertEquals(listOf("Ch. 1, \"part\" a", "a;b|c", "", "日本語 🎉", "[x]"), row.chapters)
    }

    @Test
    fun other_http_status_returns_Offline_without_moving_cursor() = runBlocking {
        seed(4, "\"e4\"")
        for (code in listOf(500, 503, 429, 403, 400)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"x"}"""))
            assertEquals("status $code", CheckResult.Offline, repo().checkForChanges())
        }
        // 3xx answers OkHttp cannot follow (no Location) are handed back and are not success either.
        for (code in listOf(300, 302, 307)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertEquals("status $code without Location", CheckResult.Offline, repo().checkForChanges())
        }
        assertEquals(SyncState(4, "\"e4\"", 1L), db.updateDao().syncState())
        assertFalse(auth.needsRepair.value)
    }

    @Test
    fun failure_on_a_later_page_stores_nothing() = runBlocking {
        seed(0)
        server.enqueue(page(listOf(1, 2), next = 2, more = true))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(CheckResult.Offline, repo().checkForChanges())
        assertEquals(SyncState(0, null, 1L), db.updateDao().syncState())
        assertTrue(db.updateDao().allById().isEmpty())
    }

    @Test
    fun cursor_moving_backwards_is_rejected() = runBlocking {
        seed(50)
        server.enqueue(page(listOf(10), next = 10, more = false))
        assertEquals(CheckResult.Offline, repo().checkForChanges())
        assertEquals(50L, db.updateDao().syncState()!!.cursor)
    }

    @Test
    fun not_paired_is_Unauthorized() = runBlocking {
        assertEquals(CheckResult.Unauthorized, repo(api = null).checkForChanges())
        assertTrue(auth.needsRepair.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun baseline_throws_and_marks_auth_on_401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val thrown = runCatching { repo().baseline() }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(auth.needsRepair.value)
        assertNull(db.updateDao().syncState())
    }

    @Test
    fun `401_from_any_api_call_sets_needsRepair`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, api().run().code())
        assertTrue(auth.needsRepair.value)
    }

    @Test
    fun feed_is_newest_first() = runBlocking {
        val cache = RoomUpdatesCache(db)
        cache.applyChanges(
            listOf(
                entity(1).copy(createdAt = "2026-11-01T10:00:00+00:00"),
                entity(2).copy(createdAt = "2026-11-03T10:00:00+00:00"),
                entity(3).copy(createdAt = "2026-11-03T10:00:00+00:00"),
            ),
            SyncState(3, null, 1L),
        )
        assertEquals(listOf(3L, 2L, 1L), repo(cache = cache).feed.first().map { it.id })
    }

    @Test
    fun stored_pairing_api_uses_the_published_snapshot_and_routes_401() = runBlocking {
        val holder = PairedCredentialsHolder()
        val client = MangaApiFactory.sharedClient(holder::get, auth::markUnauthorized)
        var ready = false
        val provider = StoredPairingApi({ ready }, holder::get, client)
        assertThrows("cold caches: Offline by exception, not 'not paired'", IllegalStateException::class.java) { provider() }

        ready = true
        assertNull("nothing published: not paired", provider())

        holder.set(base, "first")
        val api = provider()!!
        assertSame("same base reuses the API", api, provider())
        server.enqueue(latest(1))
        api.latest()
        assertEquals("Bearer first", take().getHeader("Authorization"))

        holder.set(base, "second") // a re-pair publishes base and token together
        server.enqueue(MockResponse().setResponseCode(401))
        runCatching { provider()!!.latest() }
        assertEquals("the published token is used", "Bearer second", take().getHeader("Authorization"))
        assertTrue("any 401 through the provider sets needsRepair", auth.needsRepair.value)

        holder.clear()
        assertNull(provider())
    }

    @Test
    fun paired_base_url_applies_url_policy_and_normalizes() {
        var stored: String? = "http://example.com/"
        val pairedBase = PairedBaseUrl { stored }
        assertNull("a stored URL that fails UrlPolicy gives no base", pairedBase())
        stored = "http://192.168.1.5:5000"
        assertEquals("http://192.168.1.5:5000/", pairedBase()!!.toString())
        stored = null
        assertNull(pairedBase())
    }

    // ---------------------------------------------------------------- fix round 1

    @Test
    fun throwing_api_supplier_returns_Offline() = runBlocking {
        seed(3)
        val repo = DefaultUpdatesRepository({ throw IllegalStateException("stored URL could not be read") }, RoomUpdatesCache(db), auth, { now })
        assertEquals(CheckResult.Offline, repo.checkForChanges())
        assertFalse(auth.needsRepair.value)
        assertEquals(SyncState(3, null, 1L), db.updateDao().syncState())
    }

    @Test
    fun has_more_without_cursor_progress_stops_after_one_request() = runBlocking {
        seed(0)
        // A misbehaving server: always has_more=true, next_cursor == since.
        repeat(5) { server.enqueue(page(listOf(1, 2), next = 0, more = true)) }
        val result = repo().checkForChanges() as CheckResult.NewItems
        assertEquals(listOf(1L, 2L), result.items.map { it.id })
        assertEquals("exactly one request, not up to the page cap", 1, server.requestCount)
        assertEquals("cursor does not move backwards or forwards", 0L, db.updateDao().syncState()!!.cursor)
        assertNull(db.updateDao().syncState()!!.etag)
    }

    @Test
    fun unauthorized_from_a_foreign_host_after_redirect_does_not_mark_repair() = runBlocking {
        seed(5)
        val other = MockWebServer().apply { start(InetAddress.getByName("0.0.0.0"), 0) }
        try {
            other.enqueue(MockResponse().setResponseCode(401))
            val foreign = HttpUrl.Builder().scheme("http").host("127.0.0.2").port(other.port).encodedPath("/x").build()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", foreign.toString()))
            assertEquals(CheckResult.Offline, repo().checkForChanges())
            assertFalse(auth.needsRepair.value)
            assertEquals(5L, db.updateDao().syncState()!!.cursor)
        } finally {
            runCatching { other.shutdown() }
        }
    }

    @Test
    fun pending_notifications_are_stored_undecided_items_until_marked() = runBlocking {
        seed(0)
        server.enqueue(page(listOf(1, 2, 3), next = 3, more = false))
        val repo = repo()
        assertTrue(repo.pendingNotifications().isEmpty())

        val result = repo.checkForChanges() as CheckResult.NewItems
        assertEquals(listOf(1L, 2L, 3L), result.items.map { it.id })
        // Stored, decision not taken yet: exactly what a crash right after the check would leave behind.
        assertEquals(listOf(1L, 2L, 3L), repo.pendingNotifications().map { it.id })

        repo.markNotified(listOf(1L, 3L))
        assertEquals(listOf(2L), repo.pendingNotifications().map { it.id })
        repo.markNotified(listOf(2L))
        assertTrue(repo.pendingNotifications().isEmpty())
        assertTrue(db.updateDao().allById().all { it.notified })

        // A re-delivered id keeps its decision and is not pending again.
        server.enqueue(page(listOf(2, 4), next = 4, more = false))
        val again = repo.checkForChanges() as CheckResult.NewItems
        assertEquals(listOf(4L), again.items.map { it.id })
        assertEquals(listOf(4L), repo.pendingNotifications().map { it.id })
    }

    @Test
    fun mark_notified_handles_more_ids_than_one_query_allows() = runBlocking {
        val cache = RoomUpdatesCache(db)
        cache.applyChanges((1L..1_200L).map { entity(it) }, SyncState(1_200, null, 1L))
        assertEquals(1_200, cache.pendingNotifications().size)
        cache.markNotified((1L..1_200L).toList())
        assertTrue(cache.pendingNotifications().isEmpty())
    }

    /** A MangaApi whose `changes` serves cursor-based pages; the first call waits for [gate]. */
    private class GatedApi(private val gate: CompletableDeferred<Unit>) : MangaApi {
        val sinces = java.util.Collections.synchronizedList(mutableListOf<Long>())
        @Volatile var active = 0
        @Volatile var maxActive = 0

        override suspend fun changes(since: Long, limit: Int, ifNoneMatch: String?): retrofit2.Response<ChangesDto> {
            val first = synchronized(sinces) { sinces.add(since); sinces.size == 1 }
            active++
            maxActive = maxOf(maxActive, active)
            try {
                if (first) gate.await()
                val body = if (since < 3) {
                    ChangesDto((since + 1..3L).map { UpdateDto(id = it, title = "t$it", createdAt = "2026-11-01T00:00:00+00:00") }, 3, false)
                } else {
                    ChangesDto(emptyList(), since, false)
                }
                return retrofit2.Response.success(body)
            } finally {
                active--
            }
        }

        override suspend fun latest(): LatestDto = LatestDto(0)
        override suspend fun updates(limit: Int, offset: Int, query: String?, category: String?, kind: String?): UpdatesPageDto = error("unused")
        override suspend fun status(): StatusDto = error("unused")
        override suspend fun run(): retrofit2.Response<RunDto> = error("unused")
        override suspend fun health(): HealthDto = error("unused")
        override suspend fun doubleSeries(): List<DoubleSeriesSummaryDto> = error("unused")
        override suspend fun doubleSeriesDetail(seriesId: Long): DoubleSeriesDto = error("unused")
        override suspend fun doubleCover(seriesId: Long): ResponseBody = error("unused")
        override suspend fun remediate(seriesId: Long, chapterId: Long, body: RemediateRequest): retrofit2.Response<RemediateResultDto> = error("unused")
        override suspend fun kavitaScan(seriesId: Long, body: KavitaScanRequest): retrofit2.Response<KavitaScanResultDto> = error("unused")
        override suspend fun trash(): List<TrashEntryDto> = error("unused")
        override suspend fun restoreTrash(trashId: String): retrofit2.Response<TrashEntryDto> = error("unused")
        override suspend fun doublesStatus(): DoublesStatusDto = error("unused")
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test
    fun concurrent_checks_run_one_after_the_other() = runBlocking {
        seed(0)
        val gate = CompletableDeferred<Unit>()
        val api = GatedApi(gate)
        val repo = DefaultUpdatesRepository({ api }, RoomUpdatesCache(db), auth, { now })
        val first = async { repo.checkForChanges() }
        val second = async { repo.checkForChanges() }
        val callsWhileFirstRuns: Int
        try {
            waitFor { api.sinces.size >= 1 }
            delay(200) // give the second check every chance to start its HTTP call
            callsWhileFirstRuns = api.sinces.size
        } finally {
            gate.complete(Unit)
        }
        val results = withTimeout(5_000) { listOf(first.await(), second.await()) }
        assertEquals("the second check must not call the API while the first runs", 1, callsWhileFirstRuns)
        assertEquals(1, api.maxActive)
        assertEquals("the second check starts from the first one's cursor", listOf(0L, 3L), api.sinces.toList())
        assertEquals(listOf(1L, 2L, 3L), (results[0] as CheckResult.NewItems).items.map { it.id })
        assertEquals(CheckResult.NoChange, results[1])
        assertEquals(3L, db.updateDao().syncState()!!.cursor)
    }

    @Test
    fun cancelled_check_propagates_cancellation_and_releases_the_lock() = runBlocking {
        seed(0)
        val api = GatedApi(CompletableDeferred()) // the first call never gets through
        val repo = DefaultUpdatesRepository({ api }, RoomUpdatesCache(db), auth, { now })
        val stuck = async { repo.checkForChanges() }
        waitFor { api.sinces.size >= 1 }
        stuck.cancel()
        val thrown = runCatching { stuck.await() }.exceptionOrNull()
        assertTrue("cancellation must propagate, got $thrown", thrown is CancellationException)
        val next = withTimeout(5_000) { repo.checkForChanges() }
        assertEquals(listOf(1L, 2L, 3L), (next as CheckResult.NewItems).items.map { it.id })
        assertEquals(0L + 3L, db.updateDao().syncState()!!.cursor)
    }

    // ---------------------------------------------------------------- foreground feed refresh (Task 10)

    /** `GET /api/updates` (browse, newest first). [sensitiveField] false leaves the flag out, like an older server. */
    private fun browse(ids: List<Long>, sensitive: (Long) -> Boolean = { false }, sensitiveField: Boolean = true) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
            """{"items":[${ids.joinToString(",") { id ->
                """{"id":$id,"title":"Browse $id","category":"Manga","is_new":false,"chapters":["c$id"],
                   "created_at":"2026-10-0${1 + (id % 9)}T10:00:00+00:00","thumbnail":"thumb/$id.png",
                   "kavita_url":null,"suwayomi_id":$id,"source":"x"""" +
                    (if (sensitiveField) ""","sensitive":${sensitive(id)}""" else "") + "}"
            }}],"total":${ids.size},"offset":0,"limit":60}""",
        )

    @Test
    fun empty_cache_is_filled_from_browse_endpoint_without_moving_the_cursor() = runBlocking {
        seed(50, "\"e50\"") // paired: cursor stored, nothing cached (pairing stores no items)
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"e50\""))
        server.enqueue(browse(listOf(50, 49, 48)))
        val repo = repo()

        assertEquals(FeedRefreshResult.Ok, repo.refreshFeed())

        assertEquals(listOf(50L, 49L, 48L), repo.feed.first().map { it.id }.sortedDescending())
        assertEquals("cursor and ETag belong to checkForChanges", SyncState(50, "\"e50\"", 1L), db.updateDao().syncState())
        assertEquals("/api/v1/changes?since=50&limit=50", take().path)
        assertEquals("/api/updates?limit=60&offset=0", take().path)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun fresh_install_refresh_baselines_then_browses() = runBlocking {
        server.enqueue(latest(9))
        server.enqueue(browse(listOf(9, 8)))
        assertEquals(FeedRefreshResult.Ok, repo().refreshFeed())
        assertEquals(SyncState(9, null, now), db.updateDao().syncState())
        assertEquals(listOf(8L, 9L), db.updateDao().allById().map { it.id })
        assertEquals("/api/v1/changes/latest", take().path)
        assertEquals("/api/updates?limit=60&offset=0", take().path)
    }

    @Test
    fun browse_items_are_stored_as_notified() = runBlocking {
        seed(3)
        // The check stores 4 and 5 (not decided yet); the browse page then shows 5, 4, 2 to the user.
        server.enqueue(page(listOf(4, 5), next = 5, more = false))
        server.enqueue(browse(listOf(5, 4, 2)))
        val repo = repo()

        assertEquals(FeedRefreshResult.Ok, repo.refreshFeed())

        assertEquals(listOf(2L, 4L, 5L), db.updateDao().allById().map { it.id })
        assertTrue("browse rows never notify", repo.pendingNotifications().isEmpty())
        assertEquals("the cursor still follows the change feed only", 5L, db.updateDao().syncState()!!.cursor)
        // The worker's next check finds nothing new for them.
        server.enqueue(page(emptyList(), next = 5, more = false, etag = null))
        assertEquals(CheckResult.NoChange, repo.checkForChanges())
        assertTrue(repo.pendingNotifications().isEmpty())
    }

    @Test
    fun browse_sensitive_flag_is_kept_and_a_missing_flag_counts_as_sensitive() = runBlocking {
        seed(10)
        server.enqueue(page(emptyList(), next = 10, more = false, etag = null))
        server.enqueue(browse(listOf(10, 9), sensitive = { it == 10L }))
        repo().refreshFeed()
        assertEquals(mapOf(9L to false, 10L to true), db.updateDao().allById().associate { it.id to it.sensitive })

        // An older server without the field: fail closed (blurred) rather than show a mature cover.
        server.enqueue(page(emptyList(), next = 10, more = false, etag = null))
        server.enqueue(browse(listOf(8), sensitiveField = false))
        repo().refreshFeed()
        assertTrue(db.updateDao().allById().single { it.id == 8L }.sensitive)
    }

    @Test
    fun refresh_feed_401_on_the_check_marks_repair_and_skips_the_browse() = runBlocking {
        seed(3)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        assertEquals(FeedRefreshResult.Unauthorized, repo().refreshFeed())
        assertTrue(auth.needsRepair.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun refresh_feed_401_on_the_browse_marks_repair() = runBlocking {
        seed(3)
        server.enqueue(page(emptyList(), next = 3, more = false, etag = null))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        assertEquals(FeedRefreshResult.Unauthorized, repo().refreshFeed())
        assertTrue(auth.needsRepair.value)
    }

    @Test
    fun refresh_feed_offline_or_server_error_keeps_the_cache_and_never_throws() = runBlocking {
        seed(3, "\"e3\"", 3)
        server.enqueue(page(emptyList(), next = 3, more = false, etag = null))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(FeedRefreshResult.Offline, repo().refreshFeed())
        server.enqueue(page(emptyList(), next = 3, more = false, etag = null))
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertEquals(FeedRefreshResult.Offline, repo().refreshFeed())

        val api = api()
        server.shutdown()
        assertEquals(FeedRefreshResult.Offline, repo(api = api).refreshFeed())
        assertEquals(listOf(3L), db.updateDao().allById().map { it.id })
        assertEquals(3L, db.updateDao().syncState()!!.cursor)
        assertFalse(auth.needsRepair.value)
    }

    @Test
    fun refresh_feed_waits_for_a_running_check() = runBlocking {
        seed(0)
        val gate = CompletableDeferred<Unit>()
        val api = object : MangaApi by GatedApi(gate) {
            override suspend fun updates(limit: Int, offset: Int, query: String?, category: String?, kind: String?) =
                UpdatesPageDto(emptyList())
        }
        val repo = DefaultUpdatesRepository({ api }, RoomUpdatesCache(db), auth, { now })
        val check = async { repo.checkForChanges() }
        val refresh = async { repo.refreshFeed() }
        gate.complete(Unit)
        assertTrue(withTimeout(5_000) { check.await() } is CheckResult.NewItems)
        assertEquals(FeedRefreshResult.Ok, withTimeout(5_000) { refresh.await() })
        assertEquals(3L, db.updateDao().syncState()!!.cursor)
    }
}
