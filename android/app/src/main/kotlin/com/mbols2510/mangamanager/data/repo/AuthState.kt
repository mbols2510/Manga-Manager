package com.mbols2510.mangamanager.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the device must be re-paired. Set by any 401 (the token was revoked or is unknown) and kept true
 * until [clear], which only a successful pairing calls. Share one instance across the app.
 */
class AuthState {
    private val state = MutableStateFlow(false)
    val needsRepair: StateFlow<Boolean> = state.asStateFlow()

    fun markUnauthorized() {
        state.value = true
    }

    fun clear() {
        state.value = false
    }
}
