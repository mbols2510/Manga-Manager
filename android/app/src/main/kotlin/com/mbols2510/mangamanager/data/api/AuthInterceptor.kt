package com.mbols2510.mangamanager.data.api

import com.mbols2510.mangamanager.core.UrlCheck
import com.mbols2510.mangamanager.core.UrlPolicy
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** A request URL failed [UrlPolicy]. Thrown before any network access. Its message never contains a token. */
class UrlPolicyViolation(message: String) : IOException(message)

private const val AUTHORIZATION = "Authorization"

/**
 * Enforces spec 7.3 on [url], the exact [HttpUrl] OkHttp is about to connect to. [UrlPolicy.check] is run on
 * `url.toString()`, and the host rule is re-applied directly on `url.host` so no re-parsing can change the
 * decision.
 */
internal fun enforceUrlPolicy(url: HttpUrl) {
    val check = UrlPolicy.check(url.toString())
    if (check is UrlCheck.Rejected) throw UrlPolicyViolation("Request blocked: ${check.reason}")
    if (UrlPolicy.isUnencrypted(url) && !UrlPolicy.isPrivateHost(url.host)) {
        throw UrlPolicyViolation("Request blocked: plain HTTP to a public host")
    }
}

/** Same scheme, host and port: the only destination that may receive the device token. */
internal fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port

/**
 * Application interceptor (runs once per call, before redirects):
 * - checks [UrlPolicy] on the request URL before any network access;
 * - adds `Authorization: Bearer <token>` only when the request goes to the paired server ([baseUrl]'s origin),
 *   reading the token lazily; with no token it sends no header (the server then answers 401);
 * - calls [onUnauthorized] when the final answer is a 401 from the paired server's origin.
 * It logs nothing, and [toString] does not expose the token.
 */
class AuthInterceptor(
    private val baseUrl: HttpUrl,
    private val token: () -> String?,
    private val onUnauthorized: () -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        enforceUrlPolicy(request.url)
        val builder = request.newBuilder().removeHeader(AUTHORIZATION)
        if (sameOrigin(request.url, baseUrl)) {
            usableToken(token())?.let { builder.header(AUTHORIZATION, "Bearer $it") }
        }
        val response = chain.proceed(builder.build())
        // Only the paired server's 401 says something about our token; a redirect target's 401 does not.
        if (response.code == 401 && sameOrigin(response.request.url, baseUrl)) onUnauthorized()
        return response
    }

    /** A token OkHttp would refuse as a header value is treated as missing (no exception that could carry it). */
    private fun usableToken(t: String?): String? =
        t?.takeIf { it.isNotEmpty() && it.all { c -> c in '!'..'~' } }

    override fun toString(): String = "AuthInterceptor(baseUrl=$baseUrl)"
}

/**
 * Network interceptor: runs on every hop, including each redirect hop. It re-checks [UrlPolicy], refuses a
 * hop whose scheme differs from [baseUrl]'s (HTTPS never downgrades; OkHttp is also configured not to follow
 * such redirects), and strips `Authorization` from any hop that leaves the paired server's origin. This does
 * not rely on OkHttp's own cross-host header stripping. It logs nothing.
 */
class RedirectGuardInterceptor(private val baseUrl: HttpUrl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        enforceUrlPolicy(request.url)
        if (request.url.scheme != baseUrl.scheme) {
            throw UrlPolicyViolation("Request blocked: redirect to a different scheme")
        }
        val hop = if (sameOrigin(request.url, baseUrl)) request else request.newBuilder().removeHeader(AUTHORIZATION).build()
        return chain.proceed(hop)
    }

    override fun toString(): String = "RedirectGuardInterceptor(baseUrl=$baseUrl)"
}

/**
 * Plain-HTTP requests never use a proxy: a system proxy would carry the cleartext token of a "private"
 * destination off the local network. HTTPS requests use [fallback] (the system selector).
 */
class CleartextNoProxySelector(private val fallback: ProxySelector?) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        if (uri == null || uri.scheme == null || uri.scheme.equals("http", ignoreCase = true)) return listOf(Proxy.NO_PROXY)
        return fallback?.select(uri)?.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        fallback?.connectFailed(uri, sa, ioe)
    }
}

/** Fails a response whose body exceeds [maxBytes] with an [IOException] instead of buffering it all. */
internal class BodySizeLimitInterceptor(private val maxBytes: Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body
        if (body.contentLength() > maxBytes) {
            response.close()
            throw IOException("Response body too large")
        }
        return response.newBuilder().body(LimitedBody(body, maxBytes)).build()
    }

    private class LimitedBody(private val delegate: ResponseBody, private val maxBytes: Long) : ResponseBody() {
        private val limited: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                private var total = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0) {
                        total += n
                        if (total > maxBytes) throw IOException("Response body too large")
                    }
                    return n
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = limited
    }
}
