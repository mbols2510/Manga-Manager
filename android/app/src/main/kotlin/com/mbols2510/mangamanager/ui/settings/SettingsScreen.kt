package com.mbols2510.mangamanager.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mbols2510.mangamanager.BuildConfig
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.core.UrlPolicy
import com.mbols2510.mangamanager.data.LocalTimeRange
import com.mbols2510.mangamanager.ui.common.formatEpochMillis
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import okhttp3.HttpUrl

object SettingsTestTags {
    const val REPAIR_CARD = "settings_repair_card"
    const val REPAIR_BUTTON = "settings_repair_button"
    const val SERVER_URL = "settings_server_url"
    const val UNENCRYPTED_NOTICE = "settings_unencrypted_notice"
    const val ENCRYPTED_LINE = "settings_encrypted_line"
    const val UNPAIR = "settings_unpair"
    const val UNPAIR_CONFIRM = "settings_unpair_confirm"
    const val UNPAIR_CANCEL = "settings_unpair_cancel"
    const val UNPAIR_ERROR = "settings_unpair_error"
    const val UNPAIR_RETRY = "settings_unpair_retry"
    const val IN_EFFECT = "settings_in_effect"
    const val LAST_CHECK = "settings_last_check"
    const val WIFI_ONLY = "settings_wifi_only"
    const val QUIET_HOURS = "settings_quiet_hours"
    const val QUIET_START = "settings_quiet_start"
    const val QUIET_END = "settings_quiet_end"
    const val NIGHT_STRETCH = "settings_night_stretch"
    const val NOTIFICATIONS_ON = "settings_notifications_on"
    const val NOTIFICATION_BANNER = "settings_notification_banner"
    const val NOTIFICATION_BUTTON = "settings_notification_button"
    const val CHANNEL_BLOCKED = "settings_channel_blocked"
    const val VERSION = "settings_version"

    fun interval(minutes: Int?): String = "settings_interval_${minutes ?: "off"}"
}

/** Scheme, host and (non-default) port only: what Settings shows of the paired server. Never userinfo or a path. */
fun displayOrigin(url: HttpUrl): String {
    val host = if (':' in url.host) "[${url.host}]" else url.host
    val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
    return "${url.scheme}://$host$port"
}

private enum class QuietEdge { START, END }

/**
 * Settings (spec 4.2): the paired server (read-only, with its transport notice), unpairing, the background check
 * settings, the last successful check, notification help and About. The notification state is read on ON_RESUME only.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onNavigateToPairing: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navigateToPairing by rememberUpdatedState(onNavigateToPairing)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    // One read each time the screen resumes (also when coming back from the system settings); nothing polls.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshNotificationStatus() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsEvent.RequestNotificationPermission ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openNotificationSettings(context)
                    }
                SettingsEvent.OpenNotificationSettings -> openNotificationSettings(context)
                SettingsEvent.Unpaired -> navigateToPairing()
            }
        }
    }

    var editing by rememberSaveable { mutableStateOf<QuietEdge?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (state.unpairFailed) UnpairFailedBanner(onRetry = viewModel::retryUnpair, enabled = !state.unpairing)
        if (state.needsRepair) RepairCard(onRepair = onNavigateToPairing)
        ServerSection(state, onUnpair = viewModel::requestUnpair)
        HorizontalDivider()
        ChecksSection(
            state = state,
            viewModel = viewModel,
            onEditStart = { editing = QuietEdge.START },
            onEditEnd = { editing = QuietEdge.END },
        )
        HorizontalDivider()
        NotificationsSection(state.notifications, onButton = viewModel::onNotificationButton)
        HorizontalDivider()
        AboutSection()
    }

    if (state.confirmingUnpair) {
        UnpairDialog(onConfirm = viewModel::unpair, onDismiss = viewModel::dismissUnpair)
    }

    val quiet = state.check.quietHours
    val edge = editing
    if (edge != null && quiet != null) {
        QuietTimeDialog(
            title = stringResource(if (edge == QuietEdge.START) R.string.settings_quiet_start_title else R.string.settings_quiet_end_title),
            initial = if (edge == QuietEdge.START) quiet.start else quiet.end,
            onConfirm = { time ->
                editing = null
                viewModel.setQuietHours(
                    if (edge == QuietEdge.START) LocalTimeRange(time, quiet.end) else LocalTimeRange(quiet.start, time),
                )
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun RepairCard(onRepair: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.REPAIR_CARD),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_repair_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_repair_text), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRepair, modifier = Modifier.testTag(SettingsTestTags.REPAIR_BUTTON)) {
                Text(stringResource(R.string.settings_repair_action))
            }
        }
    }
}

/** An unpair step failed: the device is already cut off (holder cleared); the user can run the cleanup again. */
@Composable
private fun UnpairFailedBanner(onRetry: () -> Unit, enabled: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.UNPAIR_ERROR),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_unpair_failed), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry, enabled = enabled, modifier = Modifier.testTag(SettingsTestTags.UNPAIR_RETRY)) {
                Text(stringResource(R.string.settings_unpair_retry))
            }
        }
    }
}

@Composable
private fun ServerSection(state: SettingsUiState, onUnpair: () -> Unit) {
    SectionTitle(stringResource(R.string.settings_server_title))
    val url = state.serverUrl
    if (!state.loaded) {
        // Nothing until the stored settings were read once: no "Not paired" flash while the cache is cold.
    } else if (url == null) {
        Text(stringResource(R.string.settings_server_none), style = MaterialTheme.typography.bodyLarge)
    } else {
        // Plain text, no field: the URL cannot be edited while a token is stored (ruling 25).
        Text(
            text = displayOrigin(url),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(SettingsTestTags.SERVER_URL),
        )
        if (UrlPolicy.isUnencrypted(url)) {
            // Persistent (spec 7.3): no dismiss action.
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.UNENCRYPTED_NOTICE),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_unencrypted_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.settings_unencrypted_text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            Text(
                text = stringResource(R.string.settings_encrypted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(SettingsTestTags.ENCRYPTED_LINE),
            )
        }
    }
    OutlinedButton(
        onClick = onUnpair,
        enabled = !state.unpairing,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.testTag(SettingsTestTags.UNPAIR),
    ) {
        Text(stringResource(if (state.unpairing) R.string.settings_unpairing else R.string.settings_unpair))
    }
}

@Composable
private fun intervalLabel(minutes: Int): String =
    if (minutes % 60 == 0) {
        pluralStringResource(R.plurals.settings_interval_hours, minutes / 60, minutes / 60)
    } else {
        pluralStringResource(R.plurals.settings_interval_minutes, minutes, minutes)
    }

@Composable
private fun ChecksSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
) {
    val check = state.check
    SectionTitle(stringResource(R.string.settings_checks_title))
    Text(
        text = stringResource(R.string.settings_battery_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(modifier = Modifier.selectableGroup()) {
        SettingsViewModel.INTERVAL_PRESETS.forEach { minutes ->
            val selected = check.intervalMinutes == minutes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { viewModel.setInterval(minutes) })
                    .testTag(SettingsTestTags.interval(minutes)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(
                    text = if (minutes == null) stringResource(R.string.settings_interval_off) else intervalLabel(minutes),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
    val interval = check.intervalMinutes
    val inEffect = when {
        !state.loaded -> ""
        state.checksPaused -> stringResource(R.string.settings_in_effect_paused)
        interval == null -> stringResource(R.string.settings_in_effect_off)
        check.wifiOnly -> stringResource(R.string.settings_in_effect_every_wifi, intervalLabel(interval))
        else -> stringResource(R.string.settings_in_effect_every, intervalLabel(interval))
    }
    Text(inEffect, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(SettingsTestTags.IN_EFFECT))

    val locale = currentLocale()
    val lastCheck = state.lastSuccessfulCheckAt?.let { formatEpochMillis(it, locale) }
        ?: stringResource(R.string.settings_last_check_never)
    Text(
        text = stringResource(R.string.settings_last_check, lastCheck),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(SettingsTestTags.LAST_CHECK),
    )

    SwitchRow(
        label = stringResource(R.string.settings_wifi_only),
        supporting = stringResource(R.string.settings_wifi_only_text),
        checked = check.wifiOnly,
        onChange = viewModel::setWifiOnly,
        tag = SettingsTestTags.WIFI_ONLY,
    )
    val quiet = check.quietHours
    SwitchRow(
        label = stringResource(R.string.settings_quiet_hours),
        supporting = stringResource(R.string.settings_quiet_hours_text),
        checked = quiet != null,
        onChange = { on -> viewModel.setQuietHours(if (on) quiet ?: SettingsViewModel.DEFAULT_QUIET_HOURS else null) },
        tag = SettingsTestTags.QUIET_HOURS,
    )
    if (quiet != null) {
        TimeRow(stringResource(R.string.settings_quiet_start), quiet.start, onEditStart, SettingsTestTags.QUIET_START)
        TimeRow(stringResource(R.string.settings_quiet_end), quiet.end, onEditEnd, SettingsTestTags.QUIET_END)
    }
    if (state.quietHoursRejected) {
        Text(
            text = stringResource(R.string.settings_quiet_same_time),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    SwitchRow(
        label = stringResource(R.string.settings_night_stretch),
        supporting = stringResource(R.string.settings_night_stretch_text),
        checked = quiet != null && check.quietNightStretch,
        onChange = viewModel::setNightStretch,
        tag = SettingsTestTags.NIGHT_STRETCH,
        enabled = quiet != null,
    )
}

@Composable
private fun SwitchRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(start = 12.dp))
    }
}

/** The UI locale, read through [LocalConfiguration] so a locale change recomposes (lint NonObservableLocale). */
@Composable
@ReadOnlyComposable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

@Composable
private fun formatTime(time: LocalTime): String {
    val context = LocalContext.current
    val locale = currentLocale()
    val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hma"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(time)
}

/** One clickable row, so TalkBack reads the label and the time together ("Start, 22:00") and opens the picker. */
@Composable
private fun TimeRow(label: String, time: LocalTime, onClick: () -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(formatTime(time), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietTimeDialog(title: String, initial: LocalTime, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pickerState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun NotificationsSection(ui: NotificationUi?, onButton: () -> Unit) {
    SectionTitle(stringResource(R.string.settings_notifications_title))
    if (ui == NotificationUi.On) {
        Text(
            text = stringResource(R.string.settings_notifications_on),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(SettingsTestTags.NOTIFICATIONS_ON),
        )
    } else {
        // The same banner as on the Updates screen, without Dismiss: Settings is where notifications are managed.
        NotificationStateBanner(
            ui = ui,
            onButton = onButton,
            bannerTag = SettingsTestTags.NOTIFICATION_BANNER,
            channelTag = SettingsTestTags.CHANNEL_BLOCKED,
            buttonTag = SettingsTestTags.NOTIFICATION_BUTTON,
        )
    }
}

@Composable
private fun AboutSection() {
    SectionTitle(stringResource(R.string.settings_about_title))
    Text(
        text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(SettingsTestTags.VERSION),
    )
    Text(
        text = stringResource(R.string.settings_about_revoke),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun UnpairDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_unpair_confirm_title)) },
        text = { Text(stringResource(R.string.settings_unpair_confirm_text)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag(SettingsTestTags.UNPAIR_CONFIRM),
            ) {
                Text(stringResource(R.string.settings_unpair_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(SettingsTestTags.UNPAIR_CANCEL)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** The app's own notification settings (an explicit system screen; no component of ours is exported). */
/** The app's notification settings (fallback: the app details screen). Also used by the Updates banner. */
internal fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } catch (_: ActivityNotFoundException) {
            // No settings screen on this device: the banner text still explains the situation.
        }
    }
}
