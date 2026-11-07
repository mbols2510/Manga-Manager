package com.mbols2510.mangamanager

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Owns the process-wide [AppContainer]. Built lazily, so nothing is opened until first used. */
class MangaApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Fill the in-memory pairing caches (URL and token) off the main thread; nothing waits for it on the main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { container.warmUp() }
        }
    }
}
