package com.mbols2510.mangamanager

import android.app.Application
import androidx.work.Configuration
import com.mbols2510.mangamanager.work.CheckWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the process-wide [AppContainer]. Built lazily, so nothing is opened until first used.
 * WorkManager is initialized on demand from [workManagerConfiguration] (its startup initializer is removed in the
 * manifest), so `CheckWorker` gets its dependencies through [CheckWorkerFactory].
 */
class MangaApp : Application(), Configuration.Provider {
    val container: AppContainer by lazy { AppContainer(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(CheckWorkerFactory { container.checkWorkerDependencies() })
            .build()

    override fun onCreate() {
        super.onCreate()
        // Fill the in-memory pairing caches (URL and token) off the main thread; nothing waits for it on the main thread.
        // Then keep the one periodic check scheduled (idempotent; the UPDATE policy keeps an unchanged schedule's timing).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                container.warmUp()
                container.scheduleChecksIfPaired()
            }
        }
    }
}
