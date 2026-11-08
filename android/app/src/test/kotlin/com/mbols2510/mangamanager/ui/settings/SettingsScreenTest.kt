package com.mbols2510.mangamanager.ui.settings

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import com.mbols2510.mangamanager.BuildConfig
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.CheckSettings
import com.mbols2510.mangamanager.data.FakeSettingsStore
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.ui.AppNavHost
import com.mbols2510.mangamanager.ui.common.formatEpochMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Compose UI tests on Robolectric (SDK 34, so POST_NOTIFICATIONS is a runtime permission). Waits are on state only. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val auth = AuthState()
    private val scheduler = RecordingScheduler()
    private var unpairs = 0
    private var toPairing = 0
    private var reads = 0

    private fun string(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    private fun vm(
        settings: FakeSettingsStore,
        status: (() -> NotificationStatus)? = null,
    ) = SettingsViewModel(
        settings = settings,
        authState = auth,
        scheduler = scheduler,
        mayScheduleChecks = { true },
        unpairAction = {
            unpairs++
            settings.clearAll()
        },
        readNotificationStatus = {
            reads++
            status?.invoke() ?: readNotificationStatus(rule.activity)
        },
        io = Dispatchers.Unconfined,
    )

    private fun show(vm: SettingsViewModel) {
        rule.setContent { MaterialTheme { SettingsScreen(viewModel = vm, onNavigateToPairing = { toPairing++ }) } }
        rule.waitForIdle()
    }

    private fun tag(t: String) = rule.onNodeWithTag(t).performScrollTo()

    private fun waitForTag(t: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun denyNotifications() = shadowOf(rule.activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun grantNotifications() = shadowOf(rule.activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    /** Pause and resume the activity: the screen re-reads the notification state on ON_RESUME. */
    private fun pauseAndResume() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
    }

    // ------------------------------------------------------------------ plan tests

    @Test
    fun unencrypted_private_http_shows_persistent_notice() {
        val settings = FakeSettingsStore("http://192.168.1.5:5000/")
        show(vm(settings, status = { STATUS_ON }))

        tag(SettingsTestTags.SERVER_URL).assertTextEquals("http://192.168.1.5:5000")
        tag(SettingsTestTags.UNENCRYPTED_NOTICE).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.settings_unencrypted_title)).assertIsDisplayed()
        rule.onAllNodesWithTag(SettingsTestTags.ENCRYPTED_LINE).assertCountEquals(0)
        rule.onAllNodesWithText(string(R.string.action_dismiss)).assertCountEquals(0) // cannot be dismissed

        // Still there after other changes and a resume.
        tag(SettingsTestTags.WIFI_ONLY).performClick()
        rule.waitForIdle()
        pauseAndResume()
        tag(SettingsTestTags.UNENCRYPTED_NOTICE).assertIsDisplayed()
    }

    @Test
    fun last_successful_check_is_shown() {
        val at = 1_793_000_000_000L
        show(vm(FakeSettingsStore("https://manga.example.com/", lastSuccessfulCheckAt = at), status = { STATUS_ON }))
        val expected = string(R.string.settings_last_check, formatEpochMillis(at))
        waitForText(expected)
        tag(SettingsTestTags.LAST_CHECK).assertTextEquals(expected)
    }

    @Test
    fun notification_permission_denied_shows_help_banner_without_repeating_prompt() {
        denyNotifications()
        show(vm(FakeSettingsStore("https://manga.example.com/")))

        waitForTag(SettingsTestTags.NOTIFICATION_BANNER)
        tag(SettingsTestTags.NOTIFICATION_BANNER).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.settings_notifications_denied)).assertIsDisplayed()
        rule.onAllNodesWithTag(SettingsTestTags.NOTIFICATIONS_ON).assertCountEquals(0)
        assertNull("opening Settings never prompts on its own", shadowOf(rule.activity).lastRequestedPermission)

        // Coming back to the screen again and again does not nag either.
        pauseAndResume()
        pauseAndResume()
        tag(SettingsTestTags.NOTIFICATION_BANNER).assertIsDisplayed()
        assertNull(shadowOf(rule.activity).lastRequestedPermission)
        assertNull("no settings screen opened on its own", shadowOf(rule.activity).nextStartedActivity)
    }

    // ------------------------------------------------------------------ server and transport

    @Test
    fun https_shows_encrypted_connection_line() {
        show(vm(FakeSettingsStore("https://manga.example.com/"), status = { STATUS_ON }))
        tag(SettingsTestTags.SERVER_URL).assertTextEquals("https://manga.example.com")
        tag(SettingsTestTags.ENCRYPTED_LINE).assertIsDisplayed()
        rule.onAllNodesWithTag(SettingsTestTags.UNENCRYPTED_NOTICE).assertCountEquals(0)
    }

    @Test
    fun server_url_is_shown_read_only_and_no_edit_field_exists() {
        show(vm(FakeSettingsStore("https://manga.example.com:8443/manga/"), status = { STATUS_ON }))
        tag(SettingsTestTags.SERVER_URL).assertTextEquals("https://manga.example.com:8443")
        tag(SettingsTestTags.SERVER_URL).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        tag(SettingsTestTags.SERVER_URL).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0) // no text field at all on the screen
        tag(SettingsTestTags.UNPAIR).assertIsDisplayed().assertTextEquals(string(R.string.settings_unpair))
    }

    @Test
    fun needs_repair_card_navigates_to_pairing() {
        show(vm(FakeSettingsStore("https://manga.example.com/", needsRepair = true), status = { STATUS_ON }))
        tag(SettingsTestTags.REPAIR_CARD).assertIsDisplayed()
        tag(SettingsTestTags.IN_EFFECT).assertTextEquals(string(R.string.settings_in_effect_paused))
        tag(SettingsTestTags.REPAIR_BUTTON).performClick()
        rule.waitForIdle()
        assertEquals(1, toPairing)
    }

    // ------------------------------------------------------------------ unpair

    @Test
    fun unpair_requires_confirmation() {
        val settings = FakeSettingsStore("https://manga.example.com/")
        val model = vm(settings, status = { STATUS_ON })
        show(model)

        tag(SettingsTestTags.UNPAIR).performClick()
        waitForTag(SettingsTestTags.UNPAIR_CONFIRM)
        rule.onNodeWithText(string(R.string.settings_unpair_confirm_title)).assertIsDisplayed()
        assertEquals("nothing happens before the confirmation", 0, unpairs)

        rule.onNodeWithTag(SettingsTestTags.UNPAIR_CANCEL).assertTextEquals(string(R.string.action_cancel)).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(SettingsTestTags.UNPAIR_CONFIRM).assertCountEquals(0)
        rule.runOnIdle { model.unpair() } // the ViewModel refuses too without the dialog
        rule.waitForIdle()
        assertEquals(0, unpairs)
        assertEquals(0, toPairing)

        tag(SettingsTestTags.UNPAIR).performClick()
        waitForTag(SettingsTestTags.UNPAIR_CONFIRM)
        rule.onNodeWithTag(SettingsTestTags.UNPAIR_CONFIRM)
            .assertTextEquals(string(R.string.settings_unpair_confirm_action))
            .performClick()
        rule.waitUntil(5_000) { toPairing == 1 }
        assertEquals(1, unpairs)
    }

    @Test
    fun after_unpair_navigation_lands_on_pairing() {
        val settings = FakeSettingsStore("https://manga.example.com/")
        val model = vm(settings, status = { STATUS_ON })
        rule.setContent {
            MaterialTheme {
                AppNavHost(
                    isPaired = true,
                    needsRepair = auth.needsRepair,
                    pairingScreen = { Text(PAIRING) },
                    settingsScreen = { toPairing -> SettingsScreen(viewModel = model, onNavigateToPairing = toPairing) },
                )
            }
        }
        rule.onNode(hasText(string(R.string.nav_settings)) and hasClickAction()).performClick()
        waitForTag(SettingsTestTags.UNPAIR)

        tag(SettingsTestTags.UNPAIR).performClick()
        waitForTag(SettingsTestTags.UNPAIR_CONFIRM)
        rule.onNodeWithTag(SettingsTestTags.UNPAIR_CONFIRM).performClick()

        waitForText(PAIRING)
        rule.onNodeWithText(PAIRING).assertIsDisplayed()
        rule.onAllNodesWithText(string(R.string.nav_settings)).assertCountEquals(0) // no bottom bar, settings popped
        rule.onAllNodesWithTag(SettingsTestTags.UNPAIR).assertCountEquals(0)
        assertEquals(1, unpairs)
        assertNull(runBlocking { settings.serverUrl() })
    }

    // ------------------------------------------------------------------ checks

    @Test
    fun interval_preset_tap_persists_and_reschedules() {
        val settings = FakeSettingsStore("https://manga.example.com/")
        show(vm(settings, status = { STATUS_ON }))
        tag(SettingsTestTags.interval(30)).assertIsSelected()
        tag(SettingsTestTags.IN_EFFECT).assertTextEquals(
            string(R.string.settings_in_effect_every, rule.activity.resources.getQuantityString(R.plurals.settings_interval_minutes, 30, 30)),
        )

        tag(SettingsTestTags.interval(120)).performClick()
        rule.waitForIdle()
        tag(SettingsTestTags.interval(120)).assertIsSelected()
        assertEquals(120, runBlocking { settings.checkSettings() }.intervalMinutes)
        assertEquals(listOf("apply:120"), scheduler.calls)
        rule.onNodeWithText(rule.activity.resources.getQuantityString(R.plurals.settings_interval_hours, 2, 2)).assertExists()

        tag(SettingsTestTags.interval(null)).performClick()
        rule.waitForIdle()
        tag(SettingsTestTags.IN_EFFECT).assertTextEquals(string(R.string.settings_in_effect_off))
        assertEquals(listOf("apply:120", "apply:null"), scheduler.calls)
        rule.onNodeWithText(string(R.string.settings_battery_hint)).assertExists()
    }

    @Test
    fun night_stretch_toggle_disabled_without_quiet_hours() {
        val settings = FakeSettingsStore("https://manga.example.com/")
        show(vm(settings, status = { STATUS_ON }))
        tag(SettingsTestTags.QUIET_HOURS).assertIsOff()
        tag(SettingsTestTags.NIGHT_STRETCH).assertIsNotEnabled()
        rule.onAllNodesWithTag(SettingsTestTags.QUIET_START).assertCountEquals(0)

        tag(SettingsTestTags.QUIET_HOURS).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(SettingsTestTags.QUIET_START).fetchSemanticsNodes().isNotEmpty() }
        tag(SettingsTestTags.QUIET_HOURS).assertIsOn()
        assertEquals(SettingsViewModel.DEFAULT_QUIET_HOURS, runBlocking { settings.checkSettings() }.quietHours)
        tag(SettingsTestTags.QUIET_START).assertIsDisplayed()
        tag(SettingsTestTags.QUIET_END).assertIsDisplayed()
        tag(SettingsTestTags.NIGHT_STRETCH).assertIsEnabled().assertIsOff().performClick()
        rule.waitForIdle()
        assertTrue(runBlocking { settings.checkSettings() }.quietNightStretch)

        tag(SettingsTestTags.QUIET_HOURS).performClick()
        rule.waitForIdle()
        tag(SettingsTestTags.NIGHT_STRETCH).assertIsNotEnabled()
        assertEquals(CheckSettings.DEFAULT, runBlocking { settings.checkSettings() })
    }

    @Test
    fun last_successful_check_never_when_null_and_live_updates() {
        val settings = FakeSettingsStore("https://manga.example.com/")
        show(vm(settings, status = { STATUS_ON }))
        val never = string(R.string.settings_last_check, string(R.string.settings_last_check_never))
        waitForText(never)
        tag(SettingsTestTags.LAST_CHECK).assertTextEquals(never)

        val at = 1_793_100_000_000L
        runBlocking { settings.setLastSuccessfulCheckAt(at) } // the worker finishing while the screen is open
        val expected = string(R.string.settings_last_check, formatEpochMillis(at))
        waitForText(expected)
        tag(SettingsTestTags.LAST_CHECK).assertTextEquals(expected)
    }

    // ------------------------------------------------------------------ notifications

    @Test
    fun permission_granted_hides_banner() {
        denyNotifications()
        show(vm(FakeSettingsStore("https://manga.example.com/")))
        waitForTag(SettingsTestTags.NOTIFICATION_BANNER)

        grantNotifications() // e.g. allowed in the system settings, then back to the app
        pauseAndResume()
        waitForTag(SettingsTestTags.NOTIFICATIONS_ON)
        tag(SettingsTestTags.NOTIFICATIONS_ON).assertTextEquals(string(R.string.settings_notifications_on))
        rule.onAllNodesWithTag(SettingsTestTags.NOTIFICATION_BANNER).assertCountEquals(0)
    }

    @Test
    fun banner_button_requests_permission_once_then_opens_settings() {
        denyNotifications()
        val settings = FakeSettingsStore("https://manga.example.com/")
        show(vm(settings))
        waitForTag(SettingsTestTags.NOTIFICATION_BUTTON)
        tag(SettingsTestTags.NOTIFICATION_BUTTON).assertTextEquals(string(R.string.settings_notifications_allow)).performClick()
        rule.waitUntil(5_000) { shadowOf(rule.activity).lastRequestedPermission != null }

        val request = shadowOf(rule.activity).lastRequestedPermission
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in request.requestedPermissions)
        assertTrue(settings.permissionRequested)
        assertNull(shadowOf(rule.activity).nextStartedActivity)

        // The user denies in the system dialog: the result goes back through the Activity Result API.
        rule.runOnUiThread {
            @Suppress("DEPRECATION")
            rule.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        }
        waitForText(string(R.string.settings_notifications_open_settings))
        tag(SettingsTestTags.NOTIFICATION_BANNER).assertIsDisplayed()

        repeat(2) {
            tag(SettingsTestTags.NOTIFICATION_BUTTON).performClick()
            rule.waitUntil(5_000) { shadowOf(rule.activity).peekNextStartedActivity() != null }
            val intent = shadowOf(rule.activity).nextStartedActivity
            assertNotNull(intent)
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
            assertEquals(rule.activity.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        }
        assertSame("the permission was requested only once", request, shadowOf(rule.activity).lastRequestedPermission)
    }

    @Test
    fun blocked_channel_hint_is_shown() {
        grantNotifications()
        rule.activity.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_NONE))
        show(vm(FakeSettingsStore("https://manga.example.com/")))

        waitForTag(SettingsTestTags.CHANNEL_BLOCKED)
        tag(SettingsTestTags.CHANNEL_BLOCKED).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.settings_notifications_channel_blocked)).assertIsDisplayed()
        tag(SettingsTestTags.NOTIFICATION_BUTTON).assertTextEquals(string(R.string.settings_notifications_open_settings)).performClick()
        rule.waitUntil(5_000) { shadowOf(rule.activity).peekNextStartedActivity() != null }
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(rule.activity).nextStartedActivity.action)
        assertNull(shadowOf(rule.activity).lastRequestedPermission)
    }

    @Test
    fun no_polling_screen_reads_state_once_on_resume() {
        show(vm(FakeSettingsStore("https://manga.example.com/"), status = { STATUS_ON }))
        rule.waitForIdle()
        assertEquals("one read when the screen resumes", 1, reads)

        rule.mainClock.advanceTimeBy(30 * 60_000L) // half an hour on screen
        rule.waitForIdle()
        assertEquals("nothing polls", 1, reads)

        pauseAndResume()
        assertEquals("one more read per resume", 2, reads)
    }

    @Test
    fun version_and_revoke_reminder_are_shown() {
        show(vm(FakeSettingsStore("https://manga.example.com/"), status = { STATUS_ON }))
        tag(SettingsTestTags.VERSION).assertTextEquals(string(R.string.settings_version, BuildConfig.VERSION_NAME))
        rule.onNodeWithText(string(R.string.settings_about_revoke)).assertExists()
    }

    private companion object {
        const val PAIRING = "pairing-slot"
        val STATUS_ON = NotificationStatus(runtimePermission = true, permissionGranted = true, appNotificationsEnabled = true, updateChannelBlocked = false)
    }
}
