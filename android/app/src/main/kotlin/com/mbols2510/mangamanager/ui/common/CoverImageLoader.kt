package com.mbols2510.mangamanager.ui.common

import android.content.Context
import coil3.ImageLoader
import okhttp3.Call

// TDD stub (Task 10).
object CoverImageLoader {
    const val DISK_CACHE_BYTES: Long = 50L * 1024 * 1024
    const val DIRECTORY = "covers"

    fun build(context: Context, callFactory: () -> Call.Factory): ImageLoader = ImageLoader.Builder(context).build()
}
