package com.mbols2510.mangamanager.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mbols2510.mangamanager.MainActivity
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.db.UpdateEntity

/**
 * Local notifications (spec 5.1, 5.3, 7.5). Three channels, created idempotently on first use:
 * - [CHANNEL_UPDATES] (default importance: a sound, never heads-up),
 * - [CHANNEL_UPDATES_SENSITIVE] (low importance: silent, so mature content never draws attention; users can mute
 *   or hide it on its own),
 * - [CHANNEL_SYSTEM] (default importance; only the one-off "Re-pair this device").
 * Every update notification is [NotificationCompat.VISIBILITY_PRIVATE] with a generic public version, has no cover
 * image, opens [MainActivity] through an immutable PendingIntent and is auto-cancelled. Nothing here logs.
 */
class LocalNotifier(context: Context) : Notifier {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    override fun canNotify(): Boolean = hasPermission() && manager.areNotificationsEnabled()

    override fun notifyUpdates(items: List<UpdateEntity>): Boolean {
        if (items.isEmpty()) return false
        val sensitive = items.any { it.sensitive }
        val channel = if (sensitive) CHANNEL_UPDATES_SENSITIVE else CHANNEL_UPDATES
        // One tag per check (its newest id): a re-post of the same check after a crash replaces, and with
        // setOnlyAlertOnce does not alert twice, instead of stacking a duplicate.
        val tag = TAG_UPDATES_PREFIX + items.maxOf { it.id }
        return post(channel, tag, ID_UPDATES) {
            val text = NotificationText.summary(context.resources, items)
            val publicVersion = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text.publicTitle)
                .setContentText(text.publicText)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text.privateTitle)
                .setContentText(text.privateText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.privateText))
                .setNumber(items.size)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setGroup(GROUP_UPDATES)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
        }
    }

    override fun notifyRepairNeeded(): Boolean = post(CHANNEL_SYSTEM, null, ID_REPAIR) {
        val title = context.getString(R.string.notification_repair_title)
        val text = context.getString(R.string.notification_repair_text)
        NotificationCompat.Builder(context, CHANNEL_SYSTEM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    /** Creates (or refreshes the names of) the three channels; the system keeps the user's choices. */
    fun ensureChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                channel(CHANNEL_UPDATES, NotificationManagerCompat.IMPORTANCE_DEFAULT,
                    R.string.notification_channel_updates, R.string.notification_channel_updates_description),
                channel(CHANNEL_UPDATES_SENSITIVE, NotificationManagerCompat.IMPORTANCE_LOW,
                    R.string.notification_channel_updates_sensitive, R.string.notification_channel_updates_sensitive_description),
                channel(CHANNEL_SYSTEM, NotificationManagerCompat.IMPORTANCE_DEFAULT,
                    R.string.notification_channel_system, R.string.notification_channel_system_description),
            ),
        )
    }

    private fun channel(id: String, importance: Int, name: Int, description: Int): NotificationChannelCompat =
        NotificationChannelCompat.Builder(id, importance)
            .setName(context.getString(name))
            .setDescription(context.getString(description))
            .build()

    private fun post(channel: String, tag: String?, id: Int, build: () -> Notification): Boolean {
        if (!canNotify()) return false
        ensureChannels()
        if (manager.getNotificationChannelCompat(channel)?.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            return false // the user turned this channel off
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            manager.notify(tag, id, build())
            true
        } catch (_: SecurityException) {
            false // permission revoked between the check and the post
        }
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Opens the app (the feed is its start screen). Explicit, immutable, no extras, no exported component added. */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_UPDATES = "updates"
        const val CHANNEL_UPDATES_SENSITIVE = "updates_sensitive"
        const val CHANNEL_SYSTEM = "system"
        const val GROUP_UPDATES = "com.mbols2510.mangamanager.UPDATES"
        const val TAG_UPDATES_PREFIX = "updates-"
        const val ID_UPDATES = 1
        const val ID_REPAIR = 2
        private const val REQUEST_OPEN_APP = 0
    }
}
