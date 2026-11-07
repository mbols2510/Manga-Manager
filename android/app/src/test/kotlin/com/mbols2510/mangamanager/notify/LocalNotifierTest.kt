package com.mbols2510.mangamanager.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import com.mbols2510.mangamanager.MainActivity
import com.mbols2510.mangamanager.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
class LocalNotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = app.getSystemService(NotificationManager::class.java)
    private val shadowManager: ShadowNotificationManager get() = shadowOf(manager)

    @Before
    fun grantNotificationPermission() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowManager.setNotificationsEnabled(true)
    }

    private fun posted(): List<Notification> = shadowManager.allNotifications

    /** Every string-ish value in a notification's extras. */
    private fun Notification.texts(): List<String> =
        extras.keySet().mapNotNull {
            @Suppress("DEPRECATION")
            extras.get(it)?.toString()
        }

    @Test
    fun channels_are_created_idempotently_and_never_heads_up() {
        val notifier = LocalNotifier(app)
        notifier.ensureChannels()
        notifier.ensureChannels()
        val channels = manager.notificationChannels.associateBy { it.id }
        assertEquals(setOf("updates", "updates_sensitive", "system"), channels.keys)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channels.getValue("updates").importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channels.getValue("updates_sensitive").importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channels.getValue("system").importance)
        assertTrue(channels.values.none { it.importance >= NotificationManager.IMPORTANCE_HIGH })
        assertEquals(app.getString(R.string.notification_channel_updates), channels.getValue("updates").name.toString())
    }

    @Test
    fun many_items_post_a_single_private_summary_with_a_generic_public_version() {
        assertTrue(LocalNotifier(app).notifyUpdates((1L..12L).map { update(it) }))
        val n = posted().single()
        assertEquals("updates", n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertNotNull(n.publicVersion)
        val public = n.publicVersion!!
        assertEquals(app.getString(R.string.notification_public_title), public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(app.getString(R.string.notification_public_text), public.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(public.texts().none { it.contains("Series") })
        assertEquals("12 new chapters", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNull("no cover image", n.getLargeIcon())
        assertEquals(R.drawable.ic_notification, n.smallIcon.resId)
        assertNull("no group key without a group summary: each check's notification is its own summary", n.group)
    }

    @Test
    fun content_intent_is_immutable_and_opens_the_app() {
        LocalNotifier(app).notifyUpdates(listOf(update(1)))
        val n = posted().single()
        for (intent in listOf(n.contentIntent, n.publicVersion!!.contentIntent)) {
            val shadow = shadowOf(intent)
            assertTrue("FLAG_IMMUTABLE", shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
            assertTrue(shadow.isActivityIntent)
            assertEquals(MainActivity::class.java.name, shadow.savedIntent.component!!.className)
            assertNull("no extras travel with the intent", shadow.savedIntent.extras)
        }
    }

    @Test
    fun sensitive_items_use_private_visibility_and_generic_public_text_without_title_or_cover() {
        assertTrue(LocalNotifier(app).notifyUpdates(listOf(update(1, title = "Secret Title", sensitive = true))))
        val n = posted().single()
        assertEquals("updates_sensitive", n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion!!
        assertEquals(app.getString(R.string.notification_public_title), public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        for (text in n.texts() + public.texts()) assertFalse(text, text.contains("Secret"))
        assertNull(n.getLargeIcon())
        assertNull(public.getLargeIcon())
        assertFalse(n.extras.containsKey(Notification.EXTRA_PICTURE))
    }

    @Test
    fun mixed_batch_posts_on_the_updates_channel() {
        assertTrue(LocalNotifier(app).notifyUpdates(listOf(update(1, title = "Alpha"), update(2, title = "Secret Title", sensitive = true))))
        val n = posted().single()
        assertEquals("updates", n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertNotNull(n.publicVersion)
        for (text in n.texts() + n.publicVersion!!.texts()) assertFalse(text, text.contains("Secret"))
    }

    @Test
    fun all_sensitive_batch_posts_on_the_sensitive_channel() {
        assertTrue(LocalNotifier(app).notifyUpdates(listOf(update(1, sensitive = true), update(2, sensitive = true))))
        val n = posted().single()
        assertEquals("updates_sensitive", n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertNotNull(n.publicVersion)
    }

    @Test
    fun hidden_sensitive_channel_does_not_swallow_non_sensitive_alerts_in_a_mixed_batch() {
        // The user turned the sensitive channel off (importance NONE); re-creating a channel never raises importance.
        manager.createNotificationChannel(
            NotificationChannel(LocalNotifier.CHANNEL_UPDATES_SENSITIVE, "Sensitive", NotificationManager.IMPORTANCE_NONE),
        )
        val notifier = LocalNotifier(app)
        notifier.ensureChannels()
        assertEquals("blocked state simulated", NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel(LocalNotifier.CHANNEL_UPDATES_SENSITIVE).importance)

        assertTrue(notifier.notifyUpdates(listOf(update(1, title = "Alpha"), update(2, sensitive = true))))
        assertEquals("updates", posted().single().channelId)

        assertFalse("an all-sensitive batch on the hidden channel is not posted",
            notifier.notifyUpdates(listOf(update(3, sensitive = true))))
        assertEquals(1, posted().size)
    }

    @Test
    fun can_notify_false_posts_nothing() {
        shadowManager.setNotificationsEnabled(false)
        val notifier = LocalNotifier(app)
        assertFalse(notifier.canNotify())
        assertFalse(notifier.notifyUpdates(listOf(update(1))))
        assertFalse(notifier.notifyRepairNeeded())
        assertTrue(posted().isEmpty())
    }

    @Test
    fun denied_permission_posts_nothing_and_does_not_throw() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = LocalNotifier(app)
        assertFalse(notifier.canNotify())
        assertFalse(notifier.notifyUpdates(listOf(update(1))))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun repair_notification_uses_the_system_channel() {
        assertTrue(LocalNotifier(app).notifyRepairNeeded())
        val n = posted().single()
        assertEquals("system", n.channelId)
        assertEquals(app.getString(R.string.notification_repair_title), n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(shadowOf(n.contentIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun empty_list_posts_nothing() {
        assertFalse(LocalNotifier(app).notifyUpdates(emptyList()))
        assertTrue(posted().isEmpty())
    }
}
