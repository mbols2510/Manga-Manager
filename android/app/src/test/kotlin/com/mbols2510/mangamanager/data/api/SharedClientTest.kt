package com.mbols2510.mangamanager.data.api

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The ONE shared client (ruling 19) over ONE credentials snapshot per request (ruling 20): every request is gated
 * at request time on the paired base and token, read together once. Plain MockWebServers on 127.0.0.1.
 */
class SharedClientTest {
    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer

    private val credentials = AtomicReference<PairedCredentials?>(null)
    private val connects = AtomicInteger()
    private var unauthorized = 0

    @Before
    fun setUp() {
        a = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        b = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @After
    fun tearDown() {
        runCatching { a.shutdown() }
        runCatching { b.shutdown() }
    }

    private fun client(source: () -> PairedCredentials? = { credentials.get() }): OkHttpClient =
        MangaApiFactory.sharedClient(source, { unauthorized++ }).newBuilder()
            .eventListener(object : EventListener() {
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    connects.incrementAndGet()
                }
            })
            .build()

    private fun OkHttpClient.get(url: HttpUrl) {
        newCall(Request.Builder().url(url).build()).execute().close()
    }

    private fun OkHttpClient.refused(url: HttpUrl): IOException = assertThrows(IOException::class.java) { get(url) }

    private fun assertBlocked(e: IOException, vararg servers: MockWebServer) {
        assertTrue("expected NotPairedException but was $e", e is NotPairedException)
        assertFalse("no token in the message", e.message.orEmpty().contains(TOKEN))
        assertEquals("no socket was opened", 0, connects.get())
        servers.forEach { assertEquals("server saw no request", 0, it.requestCount) }
    }

    private fun pair(server: MockWebServer, t: String = TOKEN) {
        credentials.set(PairedCredentials(server.url("/"), t))
    }

    @Test
    fun shared_client_blocks_request_when_cache_cold() {
        val c = client()
        assertBlocked(c.refused(a.url("/api/v1/changes/latest")), a)
    }

    @Test
    fun shared_client_blocks_request_when_not_paired() {
        val c = client()
        pair(a)
        credentials.set(null)
        assertBlocked(c.refused(a.url("/api/v1/changes/latest")), a)
    }

    @Test
    fun shared_client_blocks_request_to_other_host() {
        val c = client()
        pair(a)
        assertBlocked(c.refused(b.url("/thumb/1.png")), a, b)
        assertBlocked(c.refused("https://images.example.org/x.png".toHttpUrl()), a, b)
    }

    @Test
    fun shared_client_blocks_request_to_public_http_host() {
        val c = client()
        pair(a)
        val e = c.refused("http://example.com/x.png".toHttpUrl())
        assertTrue("UrlPolicy still runs first: $e", e is UrlPolicyViolation)
        assertEquals(0, connects.get())
        assertEquals(0, a.requestCount)
    }

    @Test
    fun same_origin_request_carries_the_bearer_header_and_401_is_reported() {
        val c = client()
        pair(a)
        a.enqueue(MockResponse().setBody("img"))
        c.get(a.url("/thumb/7.png")) // a Coil-style image request on the paired origin
        assertEquals("Bearer $TOKEN", a.takeRequest().getHeader("Authorization"))

        a.enqueue(MockResponse().setResponseCode(401))
        c.get(a.url("/api/status"))
        assertEquals(1, unauthorized)
    }

    @Test
    fun unpair_makes_the_next_request_fail() {
        val c = client()
        pair(a)
        a.enqueue(MockResponse().setBody("ok"))
        c.get(a.url("/api/status"))
        val before = connects.get()

        credentials.set(null)
        val e = c.refused(a.url("/api/status"))
        assertTrue(e is NotPairedException)
        assertEquals("no new socket after unpairing", before, connects.get())
    }

    @Test
    fun credentials_snapshot_is_read_once_per_request() {
        val reads = AtomicInteger()
        val first = PairedCredentials(a.url("/"), TOKEN)
        val second = PairedCredentials(b.url("/"), TOKEN_2)
        // The holder flips to another server right after the first read.
        val c = client { if (reads.getAndIncrement() == 0) first else second }
        a.enqueue(MockResponse().setBody("ok"))

        c.get(a.url("/api/status"))

        assertEquals("one read per request", 1, reads.get())
        assertEquals("the original base with the original token", "Bearer $TOKEN", a.takeRequest().getHeader("Authorization"))
        assertEquals(0, b.requestCount)
    }

    @Test
    fun re_pair_between_requests_never_mixes_old_token_with_new_origin_or_vice_versa() {
        val c = client()
        pair(a)
        // A request to A is in flight (A is slow, then redirects to B) while the device is re-paired to B.
        a.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", b.url("/landing").toString())
                .setHeadersDelay(1500, TimeUnit.MILLISECONDS),
        )
        b.enqueue(MockResponse().setBody("landed"))
        val inFlight = Thread { c.get(a.url("/api/status")) }.apply { start() }
        val arrived = a.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(arrived)
        assertEquals("A only ever sees the first token", "Bearer $TOKEN", arrived!!.getHeader("Authorization"))

        pair(b, TOKEN_2) // re-pair while A is still thinking
        inFlight.join(10_000)

        val landed = b.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull("the redirected request carries no Authorization to B", landed.getHeader("Authorization"))

        b.enqueue(MockResponse().setBody("ok"))
        c.get(b.url("/api/status"))
        assertEquals("B only ever sees the second token", "Bearer $TOKEN_2", b.takeRequest().getHeader("Authorization"))

        val before = connects.get()
        assertTrue("the old base is no longer accepted", c.refused(a.url("/api/status")) is NotPairedException)
        assertEquals(before, connects.get())
        assertEquals("A saw exactly one request", 1, a.requestCount)
    }

    @Test
    fun unpair_mid_flight_does_not_leak_token_to_new_origin() {
        val c = client()
        pair(a)
        a.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", b.url("/landing").toString())
                .setHeadersDelay(1500, TimeUnit.MILLISECONDS),
        )
        b.enqueue(MockResponse().setBody("landed"))
        val inFlight = Thread { runCatching { c.get(a.url("/api/status")) } }.apply { start() }
        assertEquals("Bearer $TOKEN", a.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))

        credentials.set(null) // unpair while in flight
        inFlight.join(10_000)

        b.takeRequest(2, TimeUnit.SECONDS)?.let { assertNull("no Authorization reaches B", it.getHeader("Authorization")) }
        val before = connects.get()
        assertTrue(c.refused(b.url("/api/status")) is NotPairedException)
        assertEquals(before, connects.get())
    }

    @Test
    fun redirect_guard_strips_the_header_on_hops_that_leave_the_captured_base() {
        // The base captured at the start of the call travels as a request tag; a hop to any other origin loses
        // the header, whatever a mutable supplier would say now.
        val guard = OkHttpClient.Builder().addNetworkInterceptor(RedirectGuardInterceptor { null }).build()
        val tagged = { server: MockWebServer ->
            Request.Builder().url(server.url("/x")).header("Authorization", "Bearer $TOKEN")
                .tag(PairedCredentials::class.java, PairedCredentials(a.url("/"), TOKEN)).build()
        }
        a.enqueue(MockResponse().setBody("1"))
        guard.newCall(tagged(a)).execute().close()
        assertEquals("Bearer $TOKEN", a.takeRequest().getHeader("Authorization"))

        b.enqueue(MockResponse().setBody("2"))
        guard.newCall(tagged(b)).execute().close()
        assertNull(b.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun credentials_never_print_the_token() {
        val c = PairedCredentials("https://user:pw@manga.example.com/".toHttpUrl(), TOKEN)
        assertFalse(c.toString().contains(TOKEN))
        assertFalse(c.toString().contains("pw"))
        assertFalse(PairedCredentialsHolder().apply { set(c.base, TOKEN) }.toString().contains(TOKEN))
    }

    private companion object {
        const val TOKEN = "tok_Secret-Value_0123456789abcdef"
        const val TOKEN_2 = "tok_second_0987654321"
    }
}
