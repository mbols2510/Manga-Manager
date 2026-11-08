package com.mbols2510.mangamanager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbols2510.mangamanager.data.CheckSettings
import com.mbols2510.mangamanager.data.LocalTimeRange
import com.mbols2510.mangamanager.data.SettingsStore
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.work.CheckScheduler
import java.time.LocalTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class SettingsUiState(
    /** False until the stored settings were read once. */
    val loaded: Boolean = false,
    /** The paired server (normalized at pairing); shown read-only (ruling 25). */
    val serverUrl: HttpUrl? = null,
    val check: CheckSettings = CheckSettings.DEFAULT,
    /** `SettingsStore.lastSuccessfulCheckAt`, never `SyncState.lastSuccessAt`. */
    val lastSuccessfulCheckAt: Long? = null,
    /** The server rejected the token (persisted flag or a 401 in this process). */
    val needsRepair: Boolean = false,
    /** null until the screen resumed once. */
    val notifications: NotificationUi? = null,
    /** The last quiet-hours change was refused because start and end were equal. */
    val quietHoursRejected: Boolean = false,
    val confirmingUnpair: Boolean = false,
    val unpairing: Boolean = false,
    /** The last unpair left some local data behind; Settings shows an error with "Try again". */
    val unpairFailed: Boolean = false,
) {
    /** Background checks cannot run now: not paired or waiting for a re-pair (the choice is still stored). */
    val checksPaused: Boolean get() = serverUrl == null || needsRepair
}

/** One-shot actions the screen performs (system UI and navigation). */
sealed interface SettingsEvent {
    data object RequestNotificationPermission : SettingsEvent
    data object OpenNotificationSettings : SettingsEvent
    data object Unpaired : SettingsEvent
}

/**
 * Settings (spec 4.2, 6, 7.3; ruling 25). Every check-setting change is stored, then applied with
 * [CheckScheduler.apply] (idempotent, UPDATE policy; never cancel + apply) when [mayScheduleChecks] allows it, so a
 * change made while unpaired or waiting for a re-pair only takes effect after pairing. The notification state is read
 * by [refreshNotificationStatus] (the screen calls it on ON_RESUME); nothing polls and nothing prompts on its own.
 */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val authState: AuthState,
    private val scheduler: CheckScheduler,
    private val mayScheduleChecks: suspend () -> Boolean,
    /** Runs the whole unpair; false when a step failed (the request path is cleared either way). */
    private val unpairAction: suspend () -> Boolean,
    private val readNotificationStatus: () -> NotificationStatus,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = eventChannel.receiveAsFlow()

    /** Serializes read-modify-write of the check settings, so quick taps never overwrite each other. */
    private val writeLock = Mutex()
    private var notificationStatus: NotificationStatus? = null
    private var permissionRequested = false
    private var unpairJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                settings.serverUrl,
                settings.checkSettings,
                settings.lastSuccessfulCheckAt,
                settings.needsRepair,
                authState.needsRepair,
            ) { url, check, lastCheck, persistedRepair, liveRepair ->
                Stored(url?.toHttpUrlOrNull(), check, lastCheck, persistedRepair || liveRepair)
            }.collect { s ->
                mutableState.update {
                    it.copy(
                        loaded = true,
                        serverUrl = s.url,
                        check = s.check,
                        lastSuccessfulCheckAt = s.lastCheck,
                        needsRepair = s.needsRepair,
                    )
                }
            }
        }
        viewModelScope.launch {
            val asked = guarded { withContext(io) { settings.notificationPermissionRequested() } } ?: false
            if (asked) {
                permissionRequested = true
                publishNotificationUi()
            }
        }
    }

    private data class Stored(val url: HttpUrl?, val check: CheckSettings, val lastCheck: Long?, val needsRepair: Boolean)

    // ------------------------------------------------------------------ check settings

    /** Only the presets (Off = null, 15, 30, 60, 120, 360 minutes); anything else is ignored. */
    fun setInterval(minutes: Int?) {
        if (minutes !in INTERVAL_PRESETS) return
        change { it.copy(intervalMinutes = minutes) }
    }

    fun setWifiOnly(enabled: Boolean) = change { it.copy(wifiOnly = enabled) }

    /**
     * null turns quiet hours (and the night stretch, which only works inside them) off. A range may wrap midnight
     * (22:00-07:00). A range whose start equals its end is refused (it would be an empty window that looks like
     * "always"): nothing is stored, [SettingsUiState.quietHoursRejected] is set, and false is returned.
     */
    fun setQuietHours(range: LocalTimeRange?): Boolean {
        if (range != null && range.start == range.end) {
            mutableState.update { it.copy(quietHoursRejected = true) }
            return false
        }
        mutableState.update { it.copy(quietHoursRejected = false) }
        change { it.copy(quietHours = range, quietNightStretch = range != null && it.quietNightStretch) }
        return true
    }

    /** Ignored while quiet hours are off. */
    fun setNightStretch(enabled: Boolean) = change { if (it.quietHours == null) it else it.copy(quietNightStretch = enabled) }

    private fun change(transform: (CheckSettings) -> CheckSettings) {
        viewModelScope.launch {
            writeLock.withLock {
                guarded {
                    withContext(io) {
                        val current = settings.checkSettings()
                        val next = transform(current)
                        if (next != current) {
                            settings.setCheckSettings(next)
                            if (mayScheduleChecks()) scheduler.apply(next)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ notifications

    /** One read of the system state (the screen calls it on ON_RESUME, e.g. back from the system settings). */
    fun refreshNotificationStatus() {
        notificationStatus = try {
            readNotificationStatus()
        } catch (_: RuntimeException) {
            null
        }
        publishNotificationUi()
    }

    /**
     * The banner button: the FIRST tap ever asks for the permission (the system prompt); every later tap, and every
     * state other than "permission needed", opens the app's notification settings instead. Never called on its own.
     */
    fun onNotificationButton() {
        val ui = state.value.notifications ?: return
        viewModelScope.launch {
            val ask = ui is NotificationUi.PermissionNeeded && !permissionRequested &&
                guarded { withContext(io) { settings.notificationPermissionRequested() } } != true
            if (ask) {
                permissionRequested = true
                publishNotificationUi()
                guarded { withContext(io) { settings.setNotificationPermissionRequested() } }
                eventChannel.send(SettingsEvent.RequestNotificationPermission)
            } else {
                eventChannel.send(SettingsEvent.OpenNotificationSettings)
            }
        }
    }

    /**
     * The system prompt answered. The state is re-read rather than trusting [granted] (the user may also have changed
     * the app switch); a denial only updates the banner.
     */
    fun onNotificationPermissionResult(granted: Boolean) {
        if (!granted) permissionRequested = true
        refreshNotificationStatus()
    }

    private fun publishNotificationUi() {
        val status = notificationStatus ?: return
        mutableState.update { it.copy(notifications = notificationUi(status, permissionRequested)) }
    }

    // ------------------------------------------------------------------ unpair

    fun requestUnpair() {
        if (!state.value.unpairing) mutableState.update { it.copy(confirmingUnpair = true) }
    }

    fun dismissUnpair() = mutableState.update { it.copy(confirmingUnpair = false) }

    /**
     * Runs the unpair flow, only while the confirmation dialog is showing ([requestUnpair]). Afterwards
     * [SettingsEvent.Unpaired] is sent even when a cleanup step failed: the request path is cleared first, so the
     * device is unpaired either way.
     */
    fun unpair() {
        if (!state.value.confirmingUnpair || unpairJob?.isActive == true) return
        mutableState.update { it.copy(confirmingUnpair = false, unpairing = true) }
        unpairJob = viewModelScope.launch {
            guarded { withContext(io) { unpairAction() } }
            mutableState.update { it.copy(unpairing = false) }
            eventChannel.send(SettingsEvent.Unpaired)
        }
    }

    /** "Try again" after an incomplete unpair: re-runs the (idempotent) unpair without asking again. */
    fun retryUnpair() {
    }

    /** Runs [block]; any failure except cancellation gives null. */
    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Minutes; null is "Off". Default 30 (spec 6). */
        val INTERVAL_PRESETS: List<Int?> = listOf(null, 15, 30, 60, 120, 360)

        /** What switching quiet hours on starts with. */
        val DEFAULT_QUIET_HOURS = LocalTimeRange(LocalTime.of(22, 0), LocalTime.of(7, 0))
    }
}
