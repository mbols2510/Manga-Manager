package com.mbols2510.mangamanager.ui.jobs

import androidx.lifecycle.ViewModel
import com.mbols2510.mangamanager.data.api.HealthDto
import com.mbols2510.mangamanager.data.api.StatusDto
import com.mbols2510.mangamanager.data.repo.ServerToolsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What "Run now" reported. Each server answer has its own message. */
sealed interface RunMessage {
    /** 202. */
    data object Started : RunMessage

    /** 409. */
    data object AlreadyRunning : RunMessage

    /** 403: manual runs are turned off on the server. */
    data object Disabled : RunMessage

    /** Sent, but no answer came back: the run may have started. */
    data object MaybeStarted : RunMessage

    /** Not sent: the server could not be reached. */
    data object Unreachable : RunMessage

    /** Any other error status. [text] is the server's error text, when it sent one. */
    data class Failed(val code: Int, val text: String?) : RunMessage
}

data class JobsUiState(
    val status: StatusDto? = null,
    val health: HealthDto? = null,
    val refreshing: Boolean = false,
    /** The last refresh could not reach the server. */
    val unreachable: Boolean = false,
    /** A refresh call answered with an error status (code, server text). */
    val loadError: Pair<Int, String?>? = null,
    val runInFlight: Boolean = false,
    val runMessage: RunMessage? = null,
)

/**
 * Jobs/Health (spec 4.2): "Run now", the server's scheduler status and its connection checks. Loads once when the
 * screen opens (this ViewModel is created) and on [refresh]. After a run was started the status is read ONCE more,
 * [statusRecheckDelayMs] later, so the last run shows up; nothing polls (spec 6).
 */
class JobsViewModel(
    private val repository: ServerToolsRepository,
    private val statusRecheckDelayMs: Long = STATUS_RECHECK_DELAY_MS,
) : ViewModel() {
    // STUB (red commit)
    private val mutableState = MutableStateFlow(JobsUiState())
    val state: StateFlow<JobsUiState> = mutableState.asStateFlow()

    fun refresh() = Unit
    fun runNow() = Unit
    fun dismissRunMessage() = Unit

    companion object {
        const val STATUS_RECHECK_DELAY_MS = 3_000L
    }
}
