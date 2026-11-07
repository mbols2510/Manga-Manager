package com.mbols2510.mangamanager.data.api

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The ONE shared client (ruling 19): built once, usable before anything is paired, and every request is gated
 * at request time on the paired base URL and token. Plain MockWebServers on 127.0.0.1 (a private host).
 */
class SharedClientTest {
    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer

    @Volatile private var base: HttpUrl? = null
    @Volatile private var token: String? = null
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

    private fun client(): OkHttpClient =
        MangaApiFactory.sharedClient({ base }, { token }, { unauthorized++ }).newBuilder()
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
        base = server.url("/")
        token = t
    }

    @Test
    fun shared_client_blocks_request_when_cache_cold() {
        val c = client()
        base = null
        token = TOKEN
        assertBlocked(c.refused(a.url("/api/v1/changes/latest")), a)
    }

    @Test
    fun shared_client_blocks_request_when_not_paired() {
        val c = client()
        pair(a)
        token = null
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

        base = null
        token = null
        val e = c.refused(a.url("/api/status"))
        assertTrue(e is NotPairedException)
        assertEquals("no new socket after unpairing", before, connects.get())
    }

    @Test
    fun repair_to_a_different_server_uses_the_new_base_and_token_without_a_new_client() {
        val c = client()
        pair(a)
        a.enqueue(MockResponse().setBody("one"))
        c.get(a.url("/api/status"))
        assertEquals("Bearer $TOKEN", a.takeRequest().getHeader("Authorization"))

        pair(b, "tok_second_0987654321")
        b.enqueue(MockResponse().setBody("two"))
        c.get(b.url("/api/status"))
        assertEquals("Bearer tok_second_0987654321", b.takeRequest().getHeader("Authorization"))

        val before = connects.get()
        val e = c.refused(a.url("/api/status"))
        assertTrue("the old base is no longer accepted", e is NotPairedException)
        assertEquals(before, connects.get())
        assertEquals(1, a.requestCount)
    }

    private companion object {
        const val TOKEN = "tok_Secret-Value_0123456789abcdef"
    }
}
