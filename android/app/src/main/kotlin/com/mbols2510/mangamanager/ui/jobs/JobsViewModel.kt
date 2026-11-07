package com.mbols2510.mangamanager.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbols2510.mangamanager.data.api.HealthDto
import com.mbols2510.mangamanager.data.api.StatusDto
import com.mbols2510.mangamanager.data.repo.RunOutcome
import com.mbols2510.mangamanager.data.repo.ServerToolsRepository
import com.mbols2510.mangamanager.data.repo.ToolResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val mutableState = MutableStateFlow(JobsUiState(refreshing = true))
    val state: StateFlow<JobsUiState> = mutableState.asStateFlow()

    private var refreshJob: Job? = null
    private var runJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        mutableState.update { it.copy(refreshing = true) }
        refreshJob = viewModelScope.launch {
            try {
                load()
            } finally {
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    private suspend fun load() {
        val status = when (val r = repository.status()) {
            is ToolResult.Success -> r.value
            ToolResult.Offline, ToolResult.MaybeApplied -> {
                // Unreachable: the health check would fail the same way, and an old "ok" would be misleading.
                mutableState.update { it.copy(unreachable = true, health = null) }
                return
            }
            is ToolResult.ServerError -> {
                mutableState.update { it.copy(loadError = r.code to r.message, unreachable = false) }
                null
            }
            ToolResult.Unauthorized -> return
        }
        if (status != null) mutableState.update { it.copy(status = status, unreachable = false, loadError = null) }
        when (val r = repository.health()) {
            is ToolResult.Success -> mutableState.update { it.copy(health = r.value, unreachable = false) }
            ToolResult.Offline, ToolResult.MaybeApplied -> mutableState.update { it.copy(unreachable = true, health = null) }
            is ToolResult.ServerError -> mutableState.update { it.copy(loadError = r.code to r.message, health = null) }
            ToolResult.Unauthorized -> Unit
        }
    }

    /** "Run now". A second tap while the call is in flight is ignored. */
    fun runNow() {
        if (runJob?.isActive == true) return
        mutableState.update { it.copy(runInFlight = true, runMessage = null) }
        runJob = viewModelScope.launch {
            var recheck = false
            try {
                val message = when (val r = repository.runNow()) {
                    is ToolResult.Success -> when (r.value) {
                        RunOutcome.Started -> RunMessage.Started.also { recheck = true }
                        RunOutcome.AlreadyRunning -> RunMessage.AlreadyRunning
                        RunOutcome.Disabled -> RunMessage.Disabled
                    }
                    ToolResult.MaybeApplied -> RunMessage.MaybeStarted.also { recheck = true }
                    ToolResult.Offline -> RunMessage.Unreachable
                    is ToolResult.ServerError -> RunMessage.Failed(r.code, r.message)
                    ToolResult.Unauthorized -> null
                }
                mutableState.update {
                    it.copy(runMessage = message, unreachable = if (message == RunMessage.Unreachable) true else it.unreachable)
                }
            } finally {
                mutableState.update { it.copy(runInFlight = false) }
            }
            if (recheck) recheckStatusOnce()
        }
    }

    /** Its own job, so "Run now" is usable again at once. */
    private fun recheckStatusOnce() {
        viewModelScope.launch {
            delay(statusRecheckDelayMs)
            val r = repository.status()
            if (r is ToolResult.Success) mutableState.update { it.copy(status = r.value) }
        }
    }

    fun dismissRunMessage() {
        mutableState.update { it.copy(runMessage = null) }
    }

    companion object {
        /** One status re-read after a run was started, so its "running" state shows; no polling. */
        const val STATUS_RECHECK_DELAY_MS = 3_000L
    }
}
