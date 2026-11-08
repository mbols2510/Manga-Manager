package com.mbols2510.mangamanager.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SettingsStore] for tests. */
class FakeSettingsStore(
    serverUrl: String? = null,
    checkSettings: CheckSettings = CheckSettings.DEFAULT,
    lastSuccessfulCheckAt: Long? = null,
    needsRepair: Boolean = false,
) : SettingsStore {
    private val url = MutableStateFlow(serverUrl)
    private val check = MutableStateFlow(checkSettings)
    private val lastCheck = MutableStateFlow(lastSuccessfulCheckAt)
    private val repair = MutableStateFlow(needsRepair)

    override val serverUrl: Flow<String?> = url

    override suspend fun serverUrl(): String? = url.value

    override suspend fun setServerUrl(url: String?) {
        this.url.value = url
    }

    override val checkSettings: Flow<CheckSettings> = check

    override suspend fun checkSettings(): CheckSettings = check.value

    override suspend fun setCheckSettings(settings: CheckSettings) {
        check.value = settings
    }

    override val lastSuccessfulCheckAt: Flow<Long?> = lastCheck

    override suspend fun lastSuccessfulCheckAt(): Long? = lastCheck.value

    override suspend fun setLastSuccessfulCheckAt(epochMillis: Long?) {
        lastCheck.value = epochMillis
    }

    override val needsRepair: Flow<Boolean> = repair

    override suspend fun needsRepair(): Boolean = repair.value

    override suspend fun setNeedsRepair(value: Boolean) {
        repair.value = value
    }

    var permissionRequested = false

    override suspend fun notificationPermissionRequested(): Boolean = permissionRequested

    override suspend fun setNotificationPermissionRequested() {
        permissionRequested = true
    }

    /** Like the DataStore store: everything except the notification prompt flag. */
    override suspend fun clearAll() {
        url.value = null
        check.value = CheckSettings.DEFAULT
        lastCheck.value = null
        repair.value = false
    }
}
