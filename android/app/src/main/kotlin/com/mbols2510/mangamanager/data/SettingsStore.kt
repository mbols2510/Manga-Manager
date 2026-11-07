package com.mbols2510.mangamanager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Non-secret app settings. The token is NOT here (it lives in `TokenStore`). Each field is one Flow + one suspend
 * getter + one setter.
 */
interface SettingsStore {
    /** Base URL of the paired server, always the normalized output of `UrlPolicy.check`; null when not paired. */
    val serverUrl: Flow<String?>

    suspend fun serverUrl(): String?

    /** Pass only `UrlCheck.Ok.normalized.toString()`; null removes it. */
    suspend fun setServerUrl(url: String?)

    /** Background check settings; [CheckSettings.DEFAULT] until something is stored. */
    val checkSettings: Flow<CheckSettings>

    suspend fun checkSettings(): CheckSettings

    suspend fun setCheckSettings(settings: CheckSettings)

    /**
     * Epoch millis of the last background check that reached the server and succeeded (new items or nothing new).
     * This is the "last successful check" shown in Settings; `SyncState.lastSuccessAt` is not (it only moves on writes).
     */
    val lastSuccessfulCheckAt: Flow<Long?>

    suspend fun lastSuccessfulCheckAt(): Long?

    suspend fun setLastSuccessfulCheckAt(epochMillis: Long?)

    /**
     * Persisted "the server rejected the token" flag: set by the worker on a 401, cleared by a successful pairing
     * and by [clearAll]. `AuthState` is restored from it at startup.
     */
    val needsRepair: Flow<Boolean>

    suspend fun needsRepair(): Boolean

    suspend fun setNeedsRepair(value: Boolean)

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

    // TDD stub (red commit): defaults only, writes ignored.
    override val checkSettings: Flow<CheckSettings> = prefs.map { if (STUB) CheckSettings.DEFAULT else it.toCheckSettings() }

    override suspend fun checkSettings(): CheckSettings = checkSettings.first()

    override suspend fun setCheckSettings(settings: CheckSettings) {
        if (STUB) return
        store.edit {
            // 0 stores "off" so that an absent key can keep meaning "default interval"; real values are clamped.
            it[INTERVAL_MINUTES] = settings.intervalMinutes
                ?.coerceIn(CheckSettings.MIN_INTERVAL_MINUTES, CheckSettings.MAX_INTERVAL_MINUTES)
                ?: INTERVAL_OFF
            it[WIFI_ONLY] = settings.wifiOnly
            it[NIGHT_STRETCH] = settings.quietNightStretch
            val quiet = settings.quietHours
            if (quiet == null) {
                it.remove(QUIET_START)
                it.remove(QUIET_END)
            } else {
                it[QUIET_START] = quiet.start.toSecondOfDay() / SECONDS_PER_MINUTE
                it[QUIET_END] = quiet.end.toSecondOfDay() / SECONDS_PER_MINUTE
            }
        }
    }

    override val lastSuccessfulCheckAt: Flow<Long?> = prefs.map { if (STUB) null else it[LAST_SUCCESSFUL_CHECK] }

    override suspend fun lastSuccessfulCheckAt(): Long? = lastSuccessfulCheckAt.first()

    override suspend fun setLastSuccessfulCheckAt(epochMillis: Long?) {
        store.edit { if (epochMillis == null) it.remove(LAST_SUCCESSFUL_CHECK) else it[LAST_SUCCESSFUL_CHECK] = epochMillis }
    }

    override val needsRepair: Flow<Boolean> = prefs.map { if (STUB) false else it[NEEDS_REPAIR] ?: false }

    override suspend fun needsRepair(): Boolean = needsRepair.first()

    override suspend fun setNeedsRepair(value: Boolean) {
        store.edit { if (value) it[NEEDS_REPAIR] = true else it.remove(NEEDS_REPAIR) }
    }

    override suspend fun clearAll() {
        store.edit { it.clear() }
    }

    private fun Preferences.toCheckSettings(): CheckSettings {
        val interval = this[INTERVAL_MINUTES]
        val start = this[QUIET_START]?.toLocalTimeOrNull()
        val end = this[QUIET_END]?.toLocalTimeOrNull()
        return CheckSettings(
            intervalMinutes = when (interval) {
                null -> CheckSettings.DEFAULT_INTERVAL_MINUTES
                INTERVAL_OFF -> null
                else -> interval
            },
            wifiOnly = this[WIFI_ONLY] ?: CheckSettings.DEFAULT.wifiOnly,
            quietNightStretch = this[NIGHT_STRETCH] ?: CheckSettings.DEFAULT.quietNightStretch,
            quietHours = if (start != null && end != null) LocalTimeRange(start, end) else null,
        )
    }

    private fun Int.toLocalTimeOrNull(): LocalTime? =
        if (this in 0 until MINUTES_PER_DAY) LocalTime.ofSecondOfDay(this.toLong() * SECONDS_PER_MINUTE) else null

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val INTERVAL_MINUTES = intPreferencesKey("check_interval_minutes")
        val WIFI_ONLY = booleanPreferencesKey("check_wifi_only")
        val NIGHT_STRETCH = booleanPreferencesKey("check_quiet_night_stretch")
        val QUIET_START = intPreferencesKey("quiet_hours_start_minute")
        val QUIET_END = intPreferencesKey("quiet_hours_end_minute")
        val LAST_SUCCESSFUL_CHECK = longPreferencesKey("last_successful_check_at")
        val NEEDS_REPAIR = booleanPreferencesKey("needs_repair")
        const val INTERVAL_OFF = 0
        const val STUB = true
        const val SECONDS_PER_MINUTE = 60
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/**
 * Keeps the server URL in memory so code that must not block (the shared OkHttp client, the repository's API
 * supplier) can read it from any thread. Fill it with [warmUp] (off the main thread) at startup; every write
 * through this store updates the cache after the delegate has stored the value.
 */
class CachingSettingsStore(private val delegate: SettingsStore) : SettingsStore {
    private val lock = Any()

    @Volatile
    private var loaded = false

    @Volatile
    private var url: String? = null

    /** True once the URL is known (read from the delegate or written through this store). */
    val isLoaded: Boolean get() = loaded

    /** The cached URL, never touching storage; null while cold and when not paired (see [isLoaded]). */
    val cachedServerUrl: String? get() = url

    /**
     * The cached URL, never touching storage. Throws [IllegalStateException] until [isLoaded], so a caller that
     * runs too early fails (the repository maps that to Offline) instead of reading "not paired".
     */
    fun requireCachedServerUrl(): String? {
        check(loaded) { "Settings are not loaded yet" }
        return url
    }

    suspend fun warmUp() {
        serverUrl()
    }

    override val serverUrl: Flow<String?> get() = delegate.serverUrl

    override suspend fun serverUrl(): String? {
        val read = delegate.serverUrl()
        synchronized(lock) {
            // A write through this store that finished while the read was in flight is newer: keep it.
            if (!loaded) {
                url = read
                loaded = true
            }
            return url
        }
    }

    override suspend fun setServerUrl(url: String?) {
        delegate.setServerUrl(url)
        put(url)
    }

    override val checkSettings: Flow<CheckSettings> get() = delegate.checkSettings

    override suspend fun checkSettings(): CheckSettings = delegate.checkSettings()

    override suspend fun setCheckSettings(settings: CheckSettings) = delegate.setCheckSettings(settings)

    override val lastSuccessfulCheckAt: Flow<Long?> get() = delegate.lastSuccessfulCheckAt

    override suspend fun lastSuccessfulCheckAt(): Long? = delegate.lastSuccessfulCheckAt()

    override suspend fun setLastSuccessfulCheckAt(epochMillis: Long?) = delegate.setLastSuccessfulCheckAt(epochMillis)

    override val needsRepair: Flow<Boolean> get() = delegate.needsRepair

    override suspend fun needsRepair(): Boolean = delegate.needsRepair()

    override suspend fun setNeedsRepair(value: Boolean) = delegate.setNeedsRepair(value)

    override suspend fun clearAll() {
        delegate.clearAll()
        put(null)
    }

    private fun put(value: String?) = synchronized(lock) {
        url = value
        loaded = true
    }
}
