package com.mbols2510.mangamanager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Non-secret app settings. The token is NOT here (it lives in `TokenStore`). Later tasks add their own fields
 * (check settings, quiet hours, last successful check) as one Flow + one suspend getter + one setter each.
 */
interface SettingsStore {
    /** Base URL of the paired server, always the normalized output of `UrlPolicy.check`; null when not paired. */
    val serverUrl: Flow<String?>

    suspend fun serverUrl(): String?

    /** Pass only `UrlCheck.Ok.normalized.toString()`; null removes it. */
    suspend fun setServerUrl(url: String?)

    /** Removes every stored setting (unpairing). */
    suspend fun clearAll()
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** DataStore-Preferences implementation (app-private file, excluded from backup by the data extraction rules). */
class DataStoreSettingsStore internal constructor(private val store: DataStore<Preferences>) : SettingsStore {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    // An unreadable file reads as "nothing stored" instead of crashing the app.
    private val prefs: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val serverUrl: Flow<String?> = prefs.map { it[SERVER_URL] }

    override suspend fun serverUrl(): String? = serverUrl.first()

    override suspend fun setServerUrl(url: String?) {
        store.edit { if (url == null) it.remove(SERVER_URL) else it[SERVER_URL] = url }
    }

    override suspend fun clearAll() {
        store.edit { it.clear() }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
    }
}
