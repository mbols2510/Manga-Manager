package com.mbols2510.mangamanager.ui.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import com.mbols2510.mangamanager.MangaApp
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class CoverImageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A request that skips both caches, so the loader must go to the network (its call factory). */
    private fun networkRequest(url: String) = ImageRequest.Builder(context)
        .data(url)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .coroutineContext(Dispatchers.IO)
        .build()

    /** Coil skips the network when the device looks offline; make Robolectric's active network look usable. */
    private fun forceOnline() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = checkNotNull(cm.activeNetwork) { "Robolectric has no active network" }
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(network, caps)
    }

    @Test
    fun image_loader_uses_the_shared_client_and_has_a_disk_cache_cap() = runBlocking {
        forceOnline()
        // 1) The loader's only network path is the call factory it is given, and the disk cache is capped.
        val seen = java.util.Collections.synchronizedList(mutableListOf<Request>())
        val recording = Call.Factory { request ->
            seen += request
            throw IOException("recorded")
        }
        val loader = CoverImageLoader.build(context) { recording }
        assertNotNull("disk cache", loader.diskCache)
        val disk = loader.diskCache!!
        assertEquals(50L * 1024 * 1024, disk.maxSize)
        assertEquals(CoverImageLoader.DISK_CACHE_BYTES, disk.maxSize)
        assertEquals(context.cacheDir.resolve(CoverImageLoader.DIRECTORY).toOkioPath(), disk.directory)
        assertNotNull("memory cache", loader.memoryCache)

        val result = withTimeout(10_000) { loader.execute(networkRequest("http://127.0.0.1:9/thumb/1.png")) }
        assertTrue(result is ErrorResult)
        assertEquals(listOf("http://127.0.0.1:9/thumb/1.png"), seen.map { it.url.toString() })

        // 2) The app's singleton factory hands Coil the container's ONE gated client: the token is added on the
        //    paired origin, and nothing at all is sent to another origin.
        val paired = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val other = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            paired.enqueue(MockResponse().setResponseCode(404))
            other.enqueue(MockResponse().setResponseCode(404))
            val app = ApplicationProvider.getApplicationContext<MangaApp>()
            app.container.credentials.set(paired.url("/"), "device-token")
            val appLoader = app.newImageLoader(app)
            assertEquals(CoverImageLoader.DISK_CACHE_BYTES, appLoader.diskCache?.maxSize)

            withTimeout(10_000) { appLoader.execute(networkRequest(paired.url("/thumb/7.png").toString())) }
            val sent = paired.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("the paired origin is reached", sent)
            assertEquals("/thumb/7.png", sent!!.path)
            assertEquals("Bearer device-token", sent.getHeader("Authorization"))

            val foreign = withTimeout(10_000) { appLoader.execute(networkRequest(other.url("/thumb/7.png").toString())) }
            assertTrue(foreign is ErrorResult)
            assertEquals("no request may reach another origin", 0, other.requestCount)

            app.container.credentials.clear()
            val unpaired = withTimeout(10_000) { appLoader.execute(networkRequest(paired.url("/thumb/8.png").toString())) }
            assertTrue(unpaired is ErrorResult)
            assertEquals("nothing is sent while unpaired", 1, paired.requestCount)
        } finally {
            runCatching { paired.shutdown() }
            runCatching { other.shutdown() }
        }
    }

    @Test
    fun sensitive_cover_request_decodes_tiny_and_never_shares_the_sharp_cache_entry() = runBlocking {
        val url = "http://127.0.0.1:9/thumb/1.png"
        val blurred = coverRequest(context, url, sensitive = true)
        assertEquals(Size(SENSITIVE_DECODE_PX, SENSITIVE_DECODE_PX), blurred.sizeResolver.size())
        assertEquals("never a larger bitmap than asked for", Precision.EXACT, blurred.precision)

        val sharp = coverRequest(context, url, sensitive = false)
        assertNotEquals(Size(SENSITIVE_DECODE_PX, SENSITIVE_DECODE_PX), sharp.sizeResolver.size())
        // Different memory-cache identity: a sharp bitmap of the same URL is never reused for a sensitive item.
        assertNotEquals(blurred.memoryCacheKeyExtras, sharp.memoryCacheKeyExtras)
        assertFalse(blurred.memoryCacheKeyExtras.isEmpty())
    }
}
