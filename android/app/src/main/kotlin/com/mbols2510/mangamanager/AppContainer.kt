package com.mbols2510.mangamanager

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mbols2510.mangamanager.core.CachingTokenStore
import com.mbols2510.mangamanager.core.KeystoreTokenStore
import com.mbols2510.mangamanager.core.TokenStore
import com.mbols2510.mangamanager.data.CachingSettingsStore
import com.mbols2510.mangamanager.data.DataStoreSettingsStore
import com.mbols2510.mangamanager.data.SettingsStore
import com.mbols2510.mangamanager.data.api.MangaApiFactory
import com.mbols2510.mangamanager.data.api.PairedCredentialsHolder
import com.mbols2510.mangamanager.data.db.AppDatabase
import com.mbols2510.mangamanager.data.db.RoomUpdatesCache
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.data.repo.DefaultUpdatesRepository
import com.mbols2510.mangamanager.data.repo.PairedBaseUrl
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
    /** Caches the token in memory; call [CachingTokenStore.warmUp] off the main thread at startup. */
    val tokenStore: CachingTokenStore by lazy { CachingTokenStore(tokens ?: KeystoreTokenStore(appContext)) }

    /** Caches the server URL in memory; call [CachingSettingsStore.warmUp] off the main thread at startup. */
    val settingsStore: CachingSettingsStore by lazy {
        CachingSettingsStore(settings ?: DataStoreSettingsStore(appContext))
    }
    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val credentials = PairedCredentialsHolder()

    private val baseUrl = PairedBaseUrl { settingsStore.cachedServerUrl }

    /**
     * THE OkHttpClient of the app (API calls and, in Task 10, Coil): built once, never null, never rebuilt, and it
     * reads nothing to be built. Per request it reads ONE [credentials] snapshot (base URL and token together,
     * from memory) and fails with `NotPairedException` (no socket opened) while nothing is published or the
     * destination is not the paired origin. Safe to call and to use from any thread, including the main thread:
     * no DataStore, no Keystore, no lock shared with them.
     */
    private val sharedClient: OkHttpClient by lazy {
        MangaApiFactory.sharedClient(credentials = credentials::get, onUnauthorized = authState::markUnauthorized)
    }

    fun httpClient(): OkHttpClient = sharedClient

    /**
     * Fills the in-memory caches (Keystore decrypt, DataStore read) and publishes the stored pairing, if both parts
     * are present, unless a pairing or unpairing already ran. Blocking: call on `Dispatchers.IO`.
     */
    suspend fun warmUp() {
        tokenStore.warmUp()
        settingsStore.warmUp()
        val base = baseUrl()
        val token = tokenStore.cachedToken
        if (base != null && token != null) credentials.publishIfUntouched(base, token)
    }

    /** Unpairs: the request path stops first, then the persisted token and URL are removed. Call on IO. */
    suspend fun unpair() {
        credentials.clear()
        tokenStore.clear()
        settingsStore.clearAll()
    }

    /** API supplier for the repository: Offline-by-exception while the caches are cold, null when not paired. */
    val pairingApi: StoredPairingApi by lazy {
        StoredPairingApi(
            ready = { settingsStore.isLoaded && tokenStore.isLoaded },
            credentials = credentials::get,
            client = sharedClient,
        )
    }

    /** Single instance: it holds the lock that serializes checks. */
    val updatesRepository: UpdatesRepository by lazy {
        DefaultUpdatesRepository(pairingApi, RoomUpdatesCache(database), authState)
    }

    val factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { AppViewModel(tokenStore, settingsStore, authState) }
        initializer { PairingViewModel(tokenStore, settingsStore, authState, updatesRepository, credentials) }
    }
}
