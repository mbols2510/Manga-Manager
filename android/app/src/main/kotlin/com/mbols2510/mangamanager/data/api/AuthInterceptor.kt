package com.mbols2510.mangamanager.data.api

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

// TDD stub (Task 7 RED): compiles, enforces nothing.

class UrlPolicyViolation(message: String) : IOException(message)

internal fun enforceUrlPolicy(url: HttpUrl) = Unit

internal fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = true

class AuthInterceptor(
    private val baseUrl: HttpUrl,
    private val token: () -> String?,
    private val onUnauthorized: () -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}

class RedirectGuardInterceptor(private val baseUrl: HttpUrl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}

class CleartextNoProxySelector(private val fallback: ProxySelector?) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> = fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

internal class BodySizeLimitInterceptor(private val maxBytes: Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
