package com.mbols2510.mangamanager

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mbols2510.mangamanager.core.KeystoreTokenStore
import com.mbols2510.mangamanager.core.TokenStore
import com.mbols2510.mangamanager.data.CachingSettingsStore
import com.mbols2510.mangamanager.data.DataStoreSettingsStore
import com.mbols2510.mangamanager.data.SettingsStore
import com.mbols2510.mangamanager.data.db.AppDatabase
import com.mbols2510.mangamanager.data.db.RoomUpdatesCache
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.data.repo.DefaultUpdatesRepository
import com.mbols2510.mangamanager.data.repo.StoredPairingApi
import com.mbols2510.mangamanager.data.repo.UpdatesRepository
import com.mbols2510.mangamanager.ui.AppViewModel
import com.mbols2510.mangamanager.ui.pairing.PairingViewModel
import okhttp3.OkHttpClient

/**
 * Tiny manual dependency container: one instance of each shared object per process. Everything is lazy.
 * Later tasks add their ViewModels to [factory] with one more `initializer { ... }`.
 */
class AppContainer internal constructor(
    context: Context,
    settings: SettingsStore?,
    tokens: TokenStore?,
) {
    constructor(context: Context) : this(context, null, null)

    private val appContext = context.applicationContext

    val authState: AuthState = AuthState()
    val tokenStore: TokenStore by lazy { tokens ?: KeystoreTokenStore(appContext) }

    /** Caches the server URL in memory; call [CachingSettingsStore.warmUp] off the main thread at startup. */
    val settingsStore: CachingSettingsStore by lazy {
        CachingSettingsStore(settings ?: DataStoreSettingsStore(appContext))
    }
    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    /**
     * The client for the stored pairing. It reads the URL from the in-memory cache only (no DataStore, no
     * runBlocking) and the token per request; an unfilled cache makes the repository's API supplier throw, which
     * the repository reports as Offline.
     */
    val pairingApi: StoredPairingApi by lazy {
        StoredPairingApi(storedUrl = { settingsStore.requireCachedServerUrl() }, tokenStore, authState)
    }

    /**
     * The shared OkHttpClient of the stored pairing (for Coil). Safe to call from any thread, including the main
     * thread: it never blocks and never touches DataStore or the Keystore. Returns null until the settings cache is
     * warm or when the device is not paired; ask again after pairing. Requests made with it read the token on the
     * calling thread of the request.
     */
    fun httpClient(): OkHttpClient? = if (settingsStore.isLoaded) pairingApi.client() else null

    /** Single instance: it holds the lock that serializes checks. */
    val updatesRepository: UpdatesRepository by lazy {
        DefaultUpdatesRepository(pairingApi, RoomUpdatesCache(database), authState)
    }

    val factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { AppViewModel(tokenStore, settingsStore, authState) }
        initializer { PairingViewModel(tokenStore, settingsStore, authState, updatesRepository) }
    }
}
