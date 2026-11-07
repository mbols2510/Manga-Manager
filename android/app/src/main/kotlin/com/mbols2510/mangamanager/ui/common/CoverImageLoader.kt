package com.mbols2510.mangamanager.ui.common

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import okhttp3.Call
import okio.Path.Companion.toOkioPath

/**
 * The app's one Coil [ImageLoader] (installed by `MangaApp` as Coil's singleton factory).
 *
 * - Network: ONLY through [callFactory], the container's shared gated client, which adds the token on the paired
 *   origin and fails every other request before a socket is opened (spec 7.3, 7.4). Coil's service-loaded
 *   components are switched off, so its default OkHttp fetcher with a plain client can never be picked instead.
 * - Caches (spec 6): memory (a share of the app's memory) and disk, capped at [DISK_CACHE_BYTES] in the app's
 *   cache directory (excluded from backups, cleared by the system under storage pressure).
 * - No crossfade (the default), no prefetch: an image is requested only while a cover is composed.
 */
object CoverImageLoader {
    const val DISK_CACHE_BYTES: Long = 50L * 1024 * 1024
    const val DIRECTORY = "covers"
    private const val MEMORY_SHARE = 0.15

    fun build(context: Context, callFactory: () -> Call.Factory): ImageLoader {
        val appContext = context.applicationContext
        return ImageLoader.Builder(appContext)
            .serviceLoaderEnabled(false)
            .components { add(OkHttpNetworkFetcherFactory(callFactory)) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(appContext, MEMORY_SHARE).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve(DIRECTORY).toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .build()
    }
}
