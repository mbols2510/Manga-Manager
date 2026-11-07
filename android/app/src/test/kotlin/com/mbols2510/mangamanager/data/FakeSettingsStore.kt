package com.mbols2510.mangamanager.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SettingsStore] for tests. */
class FakeSettingsStore(serverUrl: String? = null) : SettingsStore {
    private val url = MutableStateFlow(serverUrl)

    override val serverUrl: Flow<String?> = url

    override suspend fun serverUrl(): String? = url.value

    override suspend fun setServerUrl(url: String?) {
        this.url.value = url
    }

    override suspend fun clearAll() {
        url.value = null
    }
}
