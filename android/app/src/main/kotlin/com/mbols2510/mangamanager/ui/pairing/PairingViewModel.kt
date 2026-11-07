package com.mbols2510.mangamanager.ui.pairing

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.core.PairingCode
import com.mbols2510.mangamanager.core.TokenStore
import com.mbols2510.mangamanager.core.UrlCheck
import com.mbols2510.mangamanager.core.UrlPolicy
import com.mbols2510.mangamanager.data.SettingsStore
import com.mbols2510.mangamanager.data.api.LatestDto
import com.mbols2510.mangamanager.data.api.MangaApiFactory
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

/** TDD stub: behavior arrives in the next commit. */
@Suppress("UNUSED_PARAMETER", "unused")
class PairingViewModel(
    private val tokenStore: TokenStore,
    private val settings: SettingsStore,
    private val authState: AuthState,
    private val repository: UpdatesRepository,
    private val latestCall: LatestCall = FactoryLatestCall,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    fun submit(code: String) = Unit

    fun confirmAuthDisabled() = Unit

    fun dismissAuthDisabled() = Unit
}
