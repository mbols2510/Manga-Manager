package com.mbols2510.mangamanager.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mbols2510.mangamanager.R
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

/** Scheme, host and (non-default) port only: what Settings shows of the paired server. */
fun displayOrigin(url: HttpUrl): String = "" // TASK12-STUB

// TASK12-STUB: compiling stub for the RED run.
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onNavigateToPairing: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineSmall)
    }
}
