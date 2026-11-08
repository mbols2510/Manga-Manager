package com.mbols2510.mangamanager.ui.settings

import androidx.lifecycle.ViewModel
import com.mbols2510.mangamanager.data.CheckSettings
import com.mbols2510.mangamanager.data.LocalTimeRange
import com.mbols2510.mangamanager.data.SettingsStore
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.work.CheckScheduler
import java.time.LocalTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.HttpUrl

data class SettingsUiState(
    val loaded: Boolean = false,
    val serverUrl: HttpUrl? = null,
    val check: CheckSettings = CheckSettings.DEFAULT,
    val lastSuccessfulCheckAt: Long? = null,
    val needsRepair: Boolean = false,
    val notifications: NotificationUi? = null,
    val quietHoursRejected: Boolean = false,
    val confirmingUnpair: Boolean = false,
    val unpairing: Boolean = false,
) {
    val checksPaused: Boolean get() = serverUrl == null || needsRepair
}

sealed interface SettingsEvent {
    data object RequestNotificationPermission : SettingsEvent
    data object OpenNotificationSettings : SettingsEvent
    data object Unpaired : SettingsEvent
}

// TASK12-STUB: compiling stub for the RED run.
class SettingsViewModel(
    private val settings: SettingsStore,
    private val authState: AuthState,
    private val scheduler: CheckScheduler,
    private val mayScheduleChecks: suspend () -> Boolean,
    private val unpairAction: suspend () -> Unit,
    private val readNotificationStatus: () -> NotificationStatus,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = eventChannel.receiveAsFlow()

    fun setInterval(minutes: Int?) = Unit

    fun setWifiOnly(enabled: Boolean) = Unit

    fun setQuietHours(range: LocalTimeRange?): Boolean = false

    fun setNightStretch(enabled: Boolean) = Unit

    fun refreshNotificationStatus() = Unit

    fun onNotificationButton() = Unit

    fun onNotificationPermissionResult(granted: Boolean) = Unit

    fun requestUnpair() = Unit

    fun dismissUnpair() = Unit

    fun unpair() = Unit

    companion object {
        val INTERVAL_PRESETS: List<Int?> = listOf(null, 15, 30, 60, 120, 360)
        val DEFAULT_QUIET_HOURS = LocalTimeRange(LocalTime.of(22, 0), LocalTime.of(7, 0))
    }
}
