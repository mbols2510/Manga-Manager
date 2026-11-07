package com.mbols2510.mangamanager.data.api

import java.net.ProxySelector
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

// TDD stub (Task 7 RED): no URL policy, no timeouts, no redirect rules.
object MangaApiFactory {
    const val MAX_BODY_BYTES = 16L * 1024 * 1024

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun create(baseUrl: HttpUrl, token: () -> String?): MangaApi = create(baseUrl, token) {}

    fun create(baseUrl: HttpUrl, token: () -> String?, onUnauthorized: () -> Unit): MangaApi =
        createWith(baseUrl, token, onUnauthorized)

    internal fun createWith(
        baseUrl: HttpUrl,
        token: () -> String?,
        onUnauthorized: () -> Unit,
        tls: Pair<SSLSocketFactory, X509TrustManager>? = null,
        maxBodyBytes: Long = MAX_BODY_BYTES,
    ): MangaApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(buildClient(baseUrl, token, onUnauthorized, tls, maxBodyBytes))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MangaApi::class.java)

    fun client(baseUrl: HttpUrl, token: () -> String?, onUnauthorized: () -> Unit = {}): OkHttpClient =
        buildClient(baseUrl, token, onUnauthorized)

    internal fun buildClient(
        baseUrl: HttpUrl,
        token: () -> String?,
        onUnauthorized: () -> Unit,
        tls: Pair<SSLSocketFactory, X509TrustManager>? = null,
        maxBodyBytes: Long = MAX_BODY_BYTES,
    ): OkHttpClient = OkHttpClient.Builder()
        .proxySelector(CleartextNoProxySelector(ProxySelector.getDefault()))
        .addInterceptor(AuthInterceptor(baseUrl, token, onUnauthorized))
        .addInterceptor(BodySizeLimitInterceptor(maxBodyBytes))
        .addNetworkInterceptor(RedirectGuardInterceptor(baseUrl))
        .apply { tls?.let { (factory, trust) -> sslSocketFactory(factory, trust) } }
        .build()
}
