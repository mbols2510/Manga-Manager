package com.mbols2510.mangamanager.data.api

import com.mbols2510.mangamanager.core.UrlCheck
import com.mbols2510.mangamanager.core.UrlPolicy
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Builds the OkHttp client and the Retrofit [MangaApi] for a base URL and a token supplier. Used for the stored
 * pairing and for validating a candidate pairing before it is stored (Task 8).
 *
 * Transport rules (spec 6 and 7.3): 10 s connect, 15 s read/write, 30 s per call; system trust store only;
 * [UrlPolicy] on every request and every redirect hop; no HTTPS<->HTTP redirects; no proxy for plain HTTP;
 * `Authorization` only to the paired origin; response bodies capped; no logging interceptor.
 */
object MangaApiFactory {
    const val CONNECT_TIMEOUT_SECONDS = 10L
    const val READ_TIMEOUT_SECONDS = 15L
    const val WRITE_TIMEOUT_SECONDS = 15L
    const val CALL_TIMEOUT_SECONDS = 30L

    /** Far above any JSON page the server sends (200 items) and any cover; protects against a runaway body. */
    const val MAX_BODY_BYTES = 16L * 1024 * 1024

    /** Shared, tolerant JSON settings for every server response. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun create(baseUrl: HttpUrl, token: () -> String?): MangaApi = create(baseUrl, token) {}

    /** As [create], and [onUnauthorized] runs whenever any call answers 401 (wire it to `AuthState`). */
    fun create(baseUrl: HttpUrl, token: () -> String?, onUnauthorized: () -> Unit): MangaApi =
        createWith(baseUrl, token, onUnauthorized)

    internal fun createWith(
        baseUrl: HttpUrl,
        token: () -> String?,
        onUnauthorized: () -> Unit,
        maxBodyBytes: Long = MAX_BODY_BYTES,
    ): MangaApi = retrofit(checkedBase(baseUrl), buildClient(baseUrl, token, onUnauthorized, maxBodyBytes))
        .create(MangaApi::class.java)

    /** The configured client alone, e.g. for Coil to load `thumb/<id>.png` with the same rules and token. */
    fun client(baseUrl: HttpUrl, token: () -> String?, onUnauthorized: () -> Unit = {}): OkHttpClient =
        buildClient(baseUrl, token, onUnauthorized)

    /** TDD stub: the gated shared client arrives in the next commit. */
    fun sharedClient(baseUrl: () -> HttpUrl?, token: () -> String?, onUnauthorized: () -> Unit = {}): OkHttpClient =
        OkHttpClient()

    internal fun retrofit(baseUrl: HttpUrl, client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    /** The base URL normalized by [UrlPolicy]; a rejected URL throws (the message names the rule, never the token). */
    internal fun checkedBase(baseUrl: HttpUrl): HttpUrl =
        when (val checked = UrlPolicy.check(baseUrl.toString())) {
            is UrlCheck.Ok -> checked.normalized
            is UrlCheck.Rejected -> throw IllegalArgumentException("Server URL rejected: ${checked.reason}")
        }

    /** [maxBodyBytes] is a test seam (a small cap). TLS always uses the platform defaults: system trust store, standard host checks. */
    internal fun buildClient(
        baseUrl: HttpUrl,
        token: () -> String?,
        onUnauthorized: () -> Unit,
        maxBodyBytes: Long = MAX_BODY_BYTES,
    ): OkHttpClient {
        val base = checkedBase(baseUrl)
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .proxySelector(CleartextNoProxySelector(ProxySelector.getDefault()))
            .addInterceptor(AuthInterceptor(base, token, onUnauthorized))
            .addInterceptor(BodySizeLimitInterceptor(maxBodyBytes))
            .addNetworkInterceptor(RedirectGuardInterceptor(base))
            .build()
    }
}
