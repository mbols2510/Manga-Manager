package com.mbols2510.mangamanager.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mbols2510.mangamanager.notify.LocalNotifier

/** What the system lets the app show right now. Read once when Settings resumes; never polled. */
data class NotificationStatus(
    /** Android 13+: POST_NOTIFICATIONS is a runtime permission. */
    val runtimePermission: Boolean,
    /** Always true below Android 13. */
    val permissionGranted: Boolean,
    /** `NotificationManagerCompat.areNotificationsEnabled()`: the app's own switch in the system settings. */
    val appNotificationsEnabled: Boolean,
    /** The user set the `updates` or `updates_sensitive` channel to "none". */
    val updateChannelBlocked: Boolean,
)

/** What the Notifications section of Settings shows. */
sealed interface NotificationUi {
    data object On : NotificationUi

    /** Android 13+ and not granted. [canRequest]: the app never asked, so the button asks once; else it opens settings. */
    data class PermissionNeeded(val canRequest: Boolean) : NotificationUi

    /** Notifications turned off for the app (the only state below Android 13 besides [On] and [ChannelBlocked]). */
    data object TurnedOff : NotificationUi

    /** Allowed, but an update channel is blocked. */
    data object ChannelBlocked : NotificationUi
}

/** Maps the system state to what Settings shows. [alreadyRequested]: the prompt was requested once before. */
fun notificationUi(status: NotificationStatus, alreadyRequested: Boolean): NotificationUi = when {
    status.runtimePermission && !status.permissionGranted -> NotificationUi.PermissionNeeded(canRequest = !alreadyRequested)
    !status.appNotificationsEnabled -> NotificationUi.TurnedOff
    status.updateChannelBlocked -> NotificationUi.ChannelBlocked
    else -> NotificationUi.On
}

/** One read of the permission, the app switch and the two update channels. Asks for nothing. */
fun readNotificationStatus(context: Context): NotificationStatus {
    val manager = NotificationManagerCompat.from(context)
    val runtime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    val blocked = listOf(LocalNotifier.CHANNEL_UPDATES, LocalNotifier.CHANNEL_UPDATES_SENSITIVE).any {
        manager.getNotificationChannelCompat(it)?.importance == NotificationManagerCompat.IMPORTANCE_NONE
    }
    return NotificationStatus(runtime, granted, manager.areNotificationsEnabled(), blocked)
}
