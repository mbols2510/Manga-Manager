package com.mbols2510.mangamanager.ui.pairing

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.core.PairingCode
import com.mbols2510.mangamanager.core.TokenStore
import com.mbols2510.mangamanager.core.UrlCheck
import com.mbols2510.mangamanager.core.UrlPolicy
import com.mbols2510.mangamanager.data.SettingsStore
import com.mbols2510.mangamanager.data.api.LatestDto
import com.mbols2510.mangamanager.data.api.MangaApiFactory
import com.mbols2510.mangamanager.data.api.PairedCredentialsHolder
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.data.repo.UpdatesRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import retrofit2.HttpException

/** What the pairing screen shows. Never carries the pasted code or the token. */
sealed interface PairingUiState {
    data object Idle : PairingUiState
    data object Validating : PairingUiState
    data object Success : PairingUiState

    /** [message] is a string resource; when [detail] is set it fills the resource's `%1$s`. */
    data class Error(@StringRes val message: Int, val detail: String? = null) : PairingUiState

    /** The server at [host] (a private address) has authentication turned off; pairing waits for an explicit override. */
    data class ConfirmAuthDisabled(val host: String) : PairingUiState
}

/** `GET api/v1/changes/latest` against [baseUrl]; a null [token] sends no `Authorization` header. */
fun interface LatestCall {
    suspend fun latest(baseUrl: HttpUrl, token: String?): LatestDto
}

/** Production [LatestCall]: a throwaway client with the full transport rules (one or two calls per pairing). */
object FactoryLatestCall : LatestCall {
    override suspend fun latest(baseUrl: HttpUrl, token: String?): LatestDto =
        MangaApiFactory.create(baseUrl, token = { token }).latest()
}

/**
 * Pairing flow (spec 7.3, 7.4, 7.6): parse the code, check the URL, probe whether the server enforces
 * authentication, validate the candidate token, and only then store token and URL and take the baseline cursor.
 * Any failure after storing rolls both back. Nothing here logs.
 */
class PairingViewModel(
    private val tokenStore: TokenStore,
    private val settings: SettingsStore,
    private val authState: AuthState,
    private val repository: UpdatesRepository,
    private val credentials: PairedCredentialsHolder,
    private val latestCall: LatestCall = FactoryLatestCall,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    /** The parsed candidate while the auth-disabled confirmation is open. Held only in memory. */
    private var pending: Candidate? = null

    fun submit(code: String) {
        if (_state.value == PairingUiState.Validating) return
        pending = null
        val pairing = PairingCode.parse(code) ?: return fail(R.string.pairing_error_invalid_code)
        val base = when (val checked = UrlPolicy.check(pairing.url)) {
            is UrlCheck.Ok -> checked.normalized
            is UrlCheck.Rejected -> return fail(R.string.pairing_error_rejected_url, checked.reason)
        }
        val candidate = Candidate(base, pairing.token)
        _state.value = PairingUiState.Validating
        viewModelScope.launch { probeThenPair(candidate) }
    }

    /** The "I understand" override. Only reachable for a private host (see [probeThenPair]). */
    fun confirmAuthDisabled() {
        val candidate = pending ?: return
        pending = null
        if (_state.value !is PairingUiState.ConfirmAuthDisabled || !UrlPolicy.isPrivateHost(candidate.base.host)) return
        _state.value = PairingUiState.Validating
        viewModelScope.launch { pair(candidate) }
    }

    /** Declining the override is the default: refuse to pair. */
    fun dismissAuthDisabled() {
        pending = null
        if (_state.value is PairingUiState.ConfirmAuthDisabled) fail(R.string.pairing_error_auth_disabled)
    }

    private suspend fun probeThenPair(candidate: Candidate) {
        when (probe(candidate.base)) {
            Probe.AUTH_ENABLED -> pair(candidate)
            Probe.AUTH_DISABLED ->
                if (UrlPolicy.isPrivateHost(candidate.base.host)) {
                    pending = candidate
                    _state.value = PairingUiState.ConfirmAuthDisabled(candidate.base.host)
                } else {
                    fail(R.string.pairing_error_auth_disabled)
                }
            Probe.FAILED -> fail(R.string.pairing_error_connection)
        }
    }

    /** Unauthenticated probe: 401 means the server enforces auth; a 200 means anyone can use its API. */
    private suspend fun probe(base: HttpUrl): Probe = try {
        latestCall.latest(base, null)
        Probe.AUTH_DISABLED
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        if (e.code() == 401) Probe.AUTH_ENABLED else Probe.FAILED
    } catch (_: Exception) {
        Probe.FAILED
    }

    private suspend fun pair(candidate: Candidate) {
        try {
            latestCall.latest(candidate.base, candidate.token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            return if (e.code() == 401) {
                fail(R.string.pairing_error_rejected_code)
            } else {
                fail(R.string.pairing_error_server, e.code().toString())
            }
        } catch (_: Exception) {
            return fail(R.string.pairing_error_connection)
        }

        try {
            withContext(ioDispatcher) {
                // Nothing may use the old pairing, or a half-written one, while the new one is persisted.
                credentials.clear()
                tokenStore.save(candidate.token)
                settings.setServerUrl(candidate.base.toString())
                // Publish base and token to the request path together, only now that both writes succeeded.
                credentials.set(candidate.base, candidate.token)
                // The first background check must never notify about items that existed before pairing.
                repository.baseline()
                // Only now is the device really paired: a failed re-pair keeps needsRepair set.
                authState.clear()
            }
        } catch (e: CancellationException) {
            rollBack()
            throw e
        } catch (_: Exception) {
            rollBack()
            return fail(R.string.pairing_error_store)
        }
        _state.value = PairingUiState.Success
    }

    private suspend fun rollBack() = withContext(NonCancellable + ioDispatcher) {
        credentials.clear() // first: the request path stops using the candidate before the persisted copies go
        runCatching { tokenStore.clear() }
        runCatching { settings.setServerUrl(null) }
    }

    private fun fail(@StringRes message: Int, detail: String? = null) {
        _state.value = PairingUiState.Error(message, detail)
    }

    private enum class Probe { AUTH_ENABLED, AUTH_DISABLED, FAILED }

    private class Candidate(val base: HttpUrl, val token: String) {
        override fun toString(): String = "Candidate(base=$base, token=<redacted>)"
    }
}
