package com.mbols2510.mangamanager

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mbols2510.mangamanager.core.KeystoreTokenStore
import com.mbols2510.mangamanager.core.TokenStore
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
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * Tiny manual dependency container: one instance of each shared object per process. Everything is lazy.
 * Later tasks add their ViewModels to [factory] with one more `initializer { ... }`.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val authState: AuthState = AuthState()
    val tokenStore: TokenStore by lazy { KeystoreTokenStore(appContext) }
    val settingsStore: SettingsStore by lazy { DataStoreSettingsStore(appContext) }
    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    /**
     * The client for the stored pairing. StoredPairingApi's supplier is synchronous, so the stored URL is read
     * with runBlocking (DataStore caches it in memory after the first read); call the repository off the main thread.
     */
    val pairingApi: StoredPairingApi by lazy {
        StoredPairingApi(storedUrl = { runBlocking { settingsStore.serverUrl() } }, tokenStore, authState)
    }

    /** The shared OkHttpClient of the stored pairing (for Coil), or null when not paired. */
    fun httpClient(): OkHttpClient? = pairingApi.client()

    /** Single instance: it holds the lock that serializes checks. */
    val updatesRepository: UpdatesRepository by lazy {
        DefaultUpdatesRepository(pairingApi, RoomUpdatesCache(database), authState)
    }

    val factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { AppViewModel(tokenStore, settingsStore, authState) }
        initializer { PairingViewModel(tokenStore, settingsStore, authState, updatesRepository) }
    }
}
