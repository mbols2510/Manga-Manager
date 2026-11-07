package com.mbols2510.mangamanager.data.api

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Plain JVM tests: real OkHttp clients from [MangaApiFactory] against loopback MockWebServers. */
class AuthInterceptorTest {
    private val token = "secret-token-AbC123_-"
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun server(bind: String = "127.0.0.1", tls: SSLSocketFactory? = null): MockWebServer =
        MockWebServer().also { s ->
            if (tls != null) s.useHttps(tls, false)
            s.start(InetAddress.getByName(bind), 0)
            servers += s
        }

    /** Base URL by IP literal (never a DNS name), so the test does not depend on how localhost resolves. */
    private fun MockWebServer.base(scheme: String = "http", host: String = "127.0.0.1"): HttpUrl =
        HttpUrl.Builder().scheme(scheme).host(host).port(port).encodedPath("/").build()

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    // ---------------------------------------------------------------- header

    @Test
    fun header_added() = runBlocking {
        val s = server()
        s.enqueue(json("""{"cursor": 7}"""))
        val api = MangaApiFactory.create(s.base()) { token }
        assertEquals(7L, api.latest().cursor)
        val recorded = s.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer $token", recorded.getHeader("Authorization"))
        assertEquals("/api/v1/changes/latest", recorded.path)
    }

    @Test
    fun no_header_without_token_and_token_read_lazily() = runBlocking {
        val s = server()
        repeat(2) { s.enqueue(json("""{"cursor": 1}""")) }
        var current: String? = null
        val api = MangaApiFactory.create(s.base()) { current }
        api.latest()
        assertNull(s.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        current = "later-token"
        api.latest()
        assertEquals("Bearer later-token", s.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test
    fun subpath_base_url_is_kept() = runBlocking {
        val s = server()
        s.enqueue(json("""{"cursor": 3}"""))
        val base = s.base().newBuilder().encodedPath("/manga/").build()
        MangaApiFactory.create(base) { token }.latest()
        assertEquals("/manga/api/v1/changes/latest", s.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun unauthorized_answer_calls_the_callback() = runBlocking {
        val s = server()
        s.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        val calls = AtomicInteger()
        val api = MangaApiFactory.create(s.base(), { token }, { calls.incrementAndGet() })
        val status = api.run().code()
        assertEquals(401, status)
        assertEquals(1, calls.get())
    }

    // ---------------------------------------------------------------- URL policy before network

    private class NetworkSpy : EventListener() {
        val events = AtomicInteger()
        override fun dnsStart(call: Call, domainName: String) { events.incrementAndGet() }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { events.incrementAndGet() }
        override fun proxySelectStart(call: Call, url: HttpUrl) { events.incrementAndGet() }
    }

    @Test
    fun public_http_request_blocked_before_network() {
        val client = MangaApiFactory.client("http://192.168.1.5:5000/".toHttpUrl(), { token })
        val spy = NetworkSpy()
        val spied = client.newBuilder().eventListener(spy).build()
        for (url in listOf("http://example.com/api/v1/changes", "http://8.8.8.8/api/status", "http://192.168.1.1.nip.io/x")) {
            try {
                spied.newCall(Request.Builder().url(url).build()).execute().close()
                fail("expected $url to be blocked")
            } catch (e: UrlPolicyViolation) {
                assertFalse("message must not contain the token", e.message.orEmpty().contains(token))
            }
        }
        assertEquals("no DNS, proxy selection or connect may happen", 0, spy.events.get())
    }

    @Test
    fun factory_rejects_public_http_base_without_leaking_token() {
        for (bad in listOf("http://example.com/", "http://8.8.8.8:5000/")) {
            try {
                MangaApiFactory.create(bad.toHttpUrl()) { token }
                fail("expected $bad to be rejected")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message.orEmpty().contains(token))
            }
        }
    }

    @Test
    fun timeouts_and_redirect_settings() {
        val c = MangaApiFactory.client("https://manga.example.com/".toHttpUrl(), { token })
        assertEquals(10_000, c.connectTimeoutMillis)
        assertEquals(15_000, c.readTimeoutMillis)
        assertEquals(30_000, c.callTimeoutMillis)
        assertTrue(c.followRedirects)
        assertFalse("HTTPS<->HTTP redirects must never be followed", c.followSslRedirects)
        assertTrue(c.proxySelector is CleartextNoProxySelector)
        assertFalse("no token in toString", c.interceptors.joinToString().contains(token))
    }

    // ---------------------------------------------------------------- redirects

    @Test
    fun redirect_to_other_host_drops_authorization() {
        for (code in listOf(302, 307, 308)) {
            val b = server(bind = "0.0.0.0")
            b.enqueue(MockResponse().setResponseCode(200).setBody("landed"))
            val a = server()
            // 127.0.0.2 is another host (loopback, so UrlPolicy allows plain HTTP), served by b on every address.
            val target = b.base(host = "127.0.0.2").resolve("landing")!!
            a.enqueue(MockResponse().setResponseCode(code).setHeader("Location", target.toString()))

            val client = MangaApiFactory.client(a.base(), { token })
            client.newCall(Request.Builder().url(a.base().resolve("api/status")!!).build()).execute().use {
                assertEquals("redirect $code", 200, it.code)
                assertEquals("landed", it.body.string())
            }
            assertEquals("Bearer $token", a.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            val landed = b.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/landing", landed.path)
            assertNull("Authorization must not reach another host ($code)", landed.getHeader("Authorization"))
            assertFalse("no token in any header ($code)", landed.headers.toString().contains(token))
        }
    }

    @Test
    fun relative_redirect_on_same_origin_keeps_authorization() {
        val a = server()
        a.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/landing"))
        a.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        MangaApiFactory.client(a.base(), { token })
            .newCall(Request.Builder().url(a.base().resolve("api/status")!!).build()).execute().use {
                assertEquals(200, it.code)
            }
        assertEquals("Bearer $token", a.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        val second = a.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/landing", second.path)
        assertEquals("Bearer $token", second.getHeader("Authorization"))
    }

    @Test
    fun unauthorized_from_a_foreign_origin_does_not_call_the_callback() = runBlocking {
        val b = server(bind = "0.0.0.0")
        b.enqueue(MockResponse().setResponseCode(401))
        val a = server()
        a.enqueue(MockResponse().setResponseCode(302).setHeader("Location", b.base(host = "127.0.0.2").resolve("x")!!.toString()))
        a.enqueue(MockResponse().setResponseCode(401))
        val calls = AtomicInteger()
        val api = MangaApiFactory.create(a.base(), { token }, { calls.incrementAndGet() })

        assertEquals(401, api.run().code())
        assertEquals("a 401 from another host says nothing about our token", 0, calls.get())
        assertEquals(401, api.run().code())
        assertEquals("a 401 from the paired server does", 1, calls.get())
    }

    /** Our own interceptors strip the header; this does not rely on OkHttp's redirect handling. */
    @Test
    fun foreign_origin_never_gets_authorization_even_without_a_redirect() {
        val b = server(bind = "0.0.0.0")
        repeat(2) { b.enqueue(MockResponse().setResponseCode(200)) }
        val base = "http://127.0.0.1:1/".toHttpUrl()
        val foreign = b.base(host = "127.0.0.2").resolve("x")!!
        val withHeader = Request.Builder().url(foreign).header("Authorization", "Bearer $token").build()

        // Application interceptor alone (plus OkHttp), request carrying a pre-set header.
        MangaApiFactory.client(base, { token }).newCall(withHeader).execute().close()
        assertNull(b.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))

        // Network interceptor alone: it strips on any hop whose origin is not the paired server.
        OkHttpClient.Builder().addNetworkInterceptor(RedirectGuardInterceptor(base)).build()
            .newCall(withHeader).execute().close()
        assertNull(b.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test
    fun https_to_http_redirect_refused() {
        val tls = testTls()
        val plain = server()
        plain.enqueue(MockResponse().setResponseCode(200).setBody("cleartext"))
        val secure = server(tls = tls.serverFactory)
        val httpTarget = plain.base().resolve("api/status")!!
        secure.enqueue(MockResponse().setResponseCode(302).setHeader("Location", httpTarget.toString()))

        val base = secure.base(scheme = "https")
        val client = tls.trusting(MangaApiFactory.client(base, { token }))
        assertFalse("the factory's client is kept", client.followSslRedirects)
        assertTrue(client.networkInterceptors.single() is RedirectGuardInterceptor)
        client.newCall(Request.Builder().url(base.resolve("api/status")!!).build()).execute().use {
            assertEquals("the redirect is handed back, not followed", 302, it.code)
        }
        assertEquals("Bearer $token", secure.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertEquals("nothing may reach the http server", 0, plain.requestCount)

        // A direct http request through an https-paired client is refused by the hop check as well.
        try {
            client.newCall(Request.Builder().url(httpTarget).build()).execute().close()
            fail("expected a scheme change to be refused")
        } catch (e: UrlPolicyViolation) {
            assertFalse(e.message.orEmpty().contains(token))
        }
        assertEquals(0, plain.requestCount)
    }

    @Test
    fun scheme_change_on_a_redirect_hop_is_refused_even_if_ssl_redirects_were_followed() {
        val tls = testTls()
        val plain = server()
        plain.enqueue(MockResponse().setResponseCode(200).setBody("cleartext"))
        val secure = server(tls = tls.serverFactory)
        val httpTarget = plain.base().resolve("api/status")!!
        secure.enqueue(MockResponse().setResponseCode(302).setHeader("Location", httpTarget.toString()))

        val base = secure.base(scheme = "https")
        val forced = tls.trusting(MangaApiFactory.client(base, { token })).newBuilder().followSslRedirects(true).build()
        try {
            forced.newCall(Request.Builder().url(base.resolve("api/status")!!).build()).execute().close()
            fail("expected the network interceptor to refuse the http hop")
        } catch (e: UrlPolicyViolation) {
            assertFalse(e.message.orEmpty().contains(token))
        }
        assertEquals(1, secure.requestCount)
        assertEquals("nothing may reach the http server", 0, plain.requestCount)
    }

    /** Ruling 13: system trust store only. No code in src/main may be able to replace TLS trust or host checks. */
    @Test
    fun main_sources_cannot_override_tls_trust() {
        val main = File("src/main")
        val sources = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        assertTrue("no sources found under ${main.absolutePath}", sources.size > 5)
        val forbidden = listOf("sslSocketFactory", "hostnameVerifier", "X509TrustManager")
        val hits = sources.flatMap { f ->
            val text = f.readText()
            forbidden.filter { text.contains(it, ignoreCase = true) }.map { "${f.path}: $it" }
        }
        assertEquals("forbidden TLS overrides in src/main", emptyList<String>(), hits)
    }

    // ---------------------------------------------------------------- proxy selector

    @Test
    fun plain_http_never_uses_a_proxy() {
        val systemProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 3128))
        val fallback = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(systemProxy)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        val selector = CleartextNoProxySelector(fallback)
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://192.168.1.5:5000/api/v1/changes")))
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("HTTP://10.0.0.2/")))
        assertEquals(listOf(systemProxy), selector.select(URI("https://manga.example.com/")))
        assertEquals(listOf(Proxy.NO_PROXY), CleartextNoProxySelector(null).select(URI("https://manga.example.com/")))
    }

    // ---------------------------------------------------------------- body cap

    @Test
    fun oversized_body_fails_with_io_exception() {
        val s = server()
        s.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(10_000)))
        val api = MangaApiFactory.createWith(s.base(), { token }, {}, maxBodyBytes = 4_096)
        try {
            runBlocking { api.status() }
            fail("expected the oversized body to fail")
        } catch (_: IOException) {
        }
    }

    // ---------------------------------------------------------------- DTOs

    @Test
    fun update_dto_with_unknown_fields_and_nulls_parses() {
        val raw = """
            {"items":[{"id":41,"title":"Café \"Ōji\", vol. 2","category":null,"is_new":true,
              "chapters":["Ch. 1, part \"a\"", "Ch. 2"],"created_at":"2026-11-01T10:00:00+00:00",
              "thumbnail":null,"kavita_url":"https://k.example/library/1/series/2","sensitive":false,
              "suwayomi_id": 9, "replaced": [], "duplicates": [{"x": 1}], "future_field": {"a": [1, 2]}}],
             "next_cursor":41,"has_more":false,"extra":"ignored"}
        """.trimIndent()
        val dto = MangaApiFactory.json.decodeFromString(ChangesDto.serializer(), raw)
        val item = dto.items.single()
        assertEquals(41L, item.id)
        assertEquals("Café \"Ōji\", vol. 2", item.title)
        assertNull(item.category)
        assertEquals(listOf("Ch. 1, part \"a\"", "Ch. 2"), item.chapters)
        assertEquals(41L, dto.nextCursor)
    }

    @Test
    fun double_chapter_dtos_accept_numbers_or_strings() {
        val raw = """
            {"id":5,"name":"S","libraryId":2,"chapters":[{"id":10,"number":12,"range":"12","title":null,
              "isSpecial":false,"sortOrder":12.5,"volumeId":3,"volumeName":"","isLooseVolume":true,
              "files":[{"id":100,"filePath":"/m/a.cbz","bytes":123,"pages":20,"format":1,"localPath":"/m/a.cbz",
                        "modifiedAt":null,"isOfficial":true,"isNewest":true,"isLargest":false}]}]}
        """.trimIndent()
        val s = MangaApiFactory.json.decodeFromString(DoubleSeriesDto.serializer(), raw)
        val c = s.chapters.single()
        assertEquals("12", c.number)
        assertEquals("", c.title)
        assertEquals(100L, c.files.single().id)
    }

    private class TestTls(val serverFactory: SSLSocketFactory, val clientFactory: SSLSocketFactory, val trust: X509TrustManager) {
        /** The factory's client (interceptors, timeouts, proxy, redirect rules kept) trusting only the test certificate. */
        fun trusting(client: OkHttpClient): OkHttpClient = client.newBuilder().sslSocketFactory(clientFactory, trust).build()
    }

    /** A self-signed test certificate for localhost / 127.0.0.1 (src/test/resources/tls, test use only). */
    private fun testTls(): TestTls {
        val password = "test-only-not-secret".toCharArray()
        val ks = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/tls/test-server.p12")!!.use { ks.load(it, password) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, password) }
        val trustStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("server", ks.getCertificate("server"))
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
        val trust = tmf.trustManagers.single() as X509TrustManager
        val serverCtx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val clientCtx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return TestTls(serverCtx.socketFactory, clientCtx.socketFactory, trust)
    }
}
