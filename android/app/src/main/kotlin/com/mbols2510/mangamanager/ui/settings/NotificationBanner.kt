package com.mbols2510.mangamanager.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.SettingsStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object NotificationBannerTags {
    const val BANNER = "notification_banner"
    const val CHANNEL_BLOCKED = "notification_channel_blocked"
    const val BUTTON = "notification_banner_button"
    const val DISMISS = "notification_banner_dismiss"
}

/**
 * The one automatic notification-permission prompt of the app: right after a successful pairing, on Android 13+,
 * when the permission is not granted and the app never asked before (the per-install flag in [SettingsStore] that
 * the Settings banner also uses; it survives unpair on purpose). Every later request is a user's tap on a banner.
 */
class NotificationPrompt(
    private val settings: SettingsStore,
    private val readStatus: () -> NotificationStatus,
) {
    /**
     * True exactly once per install, when the prompt should be shown now; the flag is stored BEFORE returning true,
     * so a second pairing, a crash or a quick double call never prompts again. Fails closed (no prompt) on errors.
     */
    suspend fun claimAfterPairing(): Boolean = try {
        val status = readStatus()
        if (!status.runtimePermission || status.permissionGranted || settings.notificationPermissionRequested()) {
            false
        } else {
            settings.setNotificationPermissionRequested()
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}

/** In-memory, per process: the user dismissed the notification banner on the Updates screen for this session. */
class NotificationBannerSession {
    val dismissed = MutableStateFlow(false)
}

sealed interface NotificationBannerEvent {
    data object RequestPermission : NotificationBannerEvent
    data object OpenSettings : NotificationBannerEvent
}

/**
 * The notification banner outside Settings (the Updates screen). Same states and button rule as Settings
 * ([notificationUi], [SettingsViewModel.onNotificationButton]): the button asks for the permission only when the app
 * never asked, otherwise it opens the app's notification settings. The state is read by [refresh] (ON_RESUME only;
 * nothing polls) and never prompts on its own. [dismiss] hides it until the process ends.
 */
class NotificationBannerViewModel(
    private val settings: SettingsStore,
    private val readStatus: () -> NotificationStatus,
    private val session: NotificationBannerSession,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val ui = MutableStateFlow<NotificationUi?>(null)
    private var status: NotificationStatus? = null
    /** Set once this banner asked; otherwise the stored flag is re-read (the post-pairing prompt may set it later). */
    private var requested = false

    /** null while unread, when everything is on, or after [dismiss]. */
    val state: StateFlow<NotificationUi?> = combine(ui, session.dismissed) { current, dismissed ->
        current.takeUnless { dismissed || it == NotificationUi.On }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val eventChannel = Channel<NotificationBannerEvent>(Channel.BUFFERED)
    val events: Flow<NotificationBannerEvent> = eventChannel.receiveAsFlow()

    fun refresh() {
        status = try {
            readStatus()
        } catch (_: RuntimeException) {
            null
        }
        viewModelScope.launch { publish() }
    }

    fun onButton() {
        val current = ui.value ?: return
        viewModelScope.launch {
            val ask = current is NotificationUi.PermissionNeeded && !asked()
            if (ask) {
                requested = true
                guarded { withContext(io) { settings.setNotificationPermissionRequested() } }
                publish()
                eventChannel.send(NotificationBannerEvent.RequestPermission)
            } else {
                eventChannel.send(NotificationBannerEvent.OpenSettings)
            }
        }
    }

    /** The system prompt answered: re-read the state rather than trusting [granted]. */
    fun onPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) = refresh()

    fun dismiss() {
        session.dismissed.value = true
    }

    private suspend fun asked(): Boolean =
        requested || (guarded { withContext(io) { settings.notificationPermissionRequested() } } ?: true) // fail closed

    private suspend fun publish() {
        val s = status ?: return
        ui.value = notificationUi(s, asked())
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/**
 * The banner of [NotificationUi] (nothing for null and [NotificationUi.On]): permission needed, notifications off,
 * or an update channel blocked, each with the button from [notificationUi]. Shared by Settings and Updates.
 * [onDismiss] adds a Dismiss button. [bannerTag] / [channelTag] / [buttonTag] are the test tags.
 */
@Composable
fun NotificationStateBanner(
    ui: NotificationUi?,
    onButton: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    bannerTag: String = NotificationBannerTags.BANNER,
    channelTag: String = NotificationBannerTags.CHANNEL_BLOCKED,
    buttonTag: String = NotificationBannerTags.BUTTON,
) {
    val (text, button, tag) = when (ui) {
        null, NotificationUi.On -> return
        is NotificationUi.PermissionNeeded -> Triple(
            R.string.settings_notifications_denied,
            if (ui.canRequest) R.string.settings_notifications_allow else R.string.settings_notifications_open_settings,
            bannerTag,
        )
        NotificationUi.TurnedOff ->
            Triple(R.string.settings_notifications_off, R.string.settings_notifications_open_settings, bannerTag)
        NotificationUi.ChannelBlocked ->
            Triple(R.string.settings_notifications_channel_blocked, R.string.settings_notifications_open_settings, channelTag)
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onButton, modifier = Modifier.testTag(buttonTag)) { Text(stringResource(button)) }
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag(NotificationBannerTags.DISMISS)) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
            }
        }
    }
}

/** The Updates screen's banner: one read on every ON_RESUME, the permission prompt only on a tap. */
@Composable
fun UpdatesNotificationBanner(viewModel: NotificationBannerViewModel, modifier: Modifier = Modifier) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onPermissionResult(granted)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                NotificationBannerEvent.RequestPermission ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openNotificationSettings(context)
                    }
                NotificationBannerEvent.OpenSettings -> openNotificationSettings(context)
            }
        }
    }
    NotificationStateBanner(ui = ui, onButton = viewModel::onButton, onDismiss = viewModel::dismiss, modifier = modifier)
}

/**
 * The single automatic prompt after pairing ([NotificationPrompt]). Lives in the app shell, which outlives the
 * pairing screen, so the Activity Result registration is still there when the system dialog answers; the Updates
 * banner re-reads the state on ON_RESUME after the dialog.
 */
@Composable
fun PostPairingPermissionRequest(requests: Flow<Unit>) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(requests) {
        requests.collect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
