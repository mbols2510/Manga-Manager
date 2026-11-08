package com.mbols2510.mangamanager.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.core.CachingTokenStore
import com.mbols2510.mangamanager.data.CachingSettingsStore
import com.mbols2510.mangamanager.data.FakeSettingsStore
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.ui.AppViewModel
import com.mbols2510.mangamanager.ui.pairing.FakeTokenStore
import com.mbols2510.mangamanager.ui.updates.FakeFeedRepository
import com.mbols2510.mangamanager.ui.updates.UpdatesScreen
import com.mbols2510.mangamanager.ui.updates.UpdatesTestTags
import com.mbols2510.mangamanager.ui.updates.UpdatesViewModel
import com.mbols2510.mangamanager.ui.updates.feedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Final-review fix A: the one automatic notification prompt right after pairing, and the notification banner on the
 * Updates screen (Robolectric SDK 34, so POST_NOTIFICATIONS is a runtime permission). Waits are on state only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class NotificationBannerTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var reads = 0

    private fun string(id: Int): String = rule.activity.getString(id)

    private fun denyNotifications() = shadowOf(rule.activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun grantNotifications() = shadowOf(rule.activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun systemStatus(): NotificationStatus {
        reads++
        return readNotificationStatus(rule.activity)
    }

    /** Drains the activities started so far and returns their actions, oldest first. */
    private fun startedActions(): List<String?> =
        generateSequence { shadowOf(rule.activity).nextStartedActivity }.map { it.action }.toList()

    private fun pauseAndResume() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
    }

    private fun waitForTag(t: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    // ------------------------------------------------------------------ the prompt after pairing

    private fun appViewModel(settings: FakeSettingsStore, status: () -> NotificationStatus = { systemStatus() }) =
        AppViewModel(
            CachingTokenStore(FakeTokenStore()), CachingSettingsStore(settings), AuthState(),
            ioDispatcher = Dispatchers.Unconfined,
            notificationPrompt = NotificationPrompt(settings, status),
        )

    private fun showShell(vm: AppViewModel) {
        rule.setContent { PostPairingPermissionRequest(vm.notificationPermissionRequests) }
        rule.waitForIdle()
    }

    @Test
    fun pairing_success_requests_the_permission_exactly_once() {
        denyNotifications()
        val settings = FakeSettingsStore()
        val vm = appViewModel(settings)
        showShell(vm)
        assertNull("nothing is requested before pairing", shadowOf(rule.activity).lastRequestedPermission)

        rule.runOnUiThread { vm.onPaired() }
        rule.waitUntil(5_000) { shadowOf(rule.activity).lastRequestedPermission != null }
        val request = shadowOf(rule.activity).lastRequestedPermission
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in request.requestedPermissions)
        assertTrue("the per-install flag is recorded", settings.permissionRequested)
        assertEquals("one system prompt", listOf(REQUEST_PERMISSIONS_ACTION), startedActions())

        // The user denies; later the device is unpaired (the flag survives clearAll) and paired again.
        rule.runOnUiThread {
            @Suppress("DEPRECATION")
            rule.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        }
        runBlocking { settings.clearAll() }
        assertTrue(settings.permissionRequested)
        rule.runOnUiThread { vm.onPaired() }
        rule.waitForIdle()
        assertSame("no second automatic request", request, shadowOf(rule.activity).lastRequestedPermission)
        assertTrue(startedActions().isEmpty())

        // A fresh app start (same stored flag) does not claim the prompt either.
        assertFalse(runBlocking { NotificationPrompt(settings) { systemStatus() }.claimAfterPairing() })
    }

    @Test
    fun no_prompt_below_android_13_or_when_already_granted() = runBlocking {
        val below13 = FakeSettingsStore()
        val prompt = NotificationPrompt(below13) { NotificationStatus(false, true, true, false) }
        assertFalse("not a runtime permission before Android 13", prompt.claimAfterPairing())
        assertFalse("nothing recorded, nothing asked", below13.permissionRequested)

        val granted = FakeSettingsStore()
        assertFalse(NotificationPrompt(granted) { NotificationStatus(true, true, true, false) }.claimAfterPairing())
        assertFalse(granted.permissionRequested)

        val failing = FakeSettingsStore()
        assertFalse("a failing read never prompts", NotificationPrompt(failing) { error("boom") }.claimAfterPairing())

        val api33 = FakeSettingsStore()
        val denied = NotificationPrompt(api33) { NotificationStatus(true, false, true, false) }
        assertTrue(denied.claimAfterPairing())
        assertTrue(api33.permissionRequested)
        assertFalse("once per install", denied.claimAfterPairing())
    }

    @Test
    fun pairing_below_android_13_starts_no_request() {
        val settings = FakeSettingsStore()
        val vm = appViewModel(settings) { NotificationStatus(false, true, true, false) }
        showShell(vm)
        rule.runOnUiThread { vm.onPaired() }
        rule.waitForIdle()
        assertNull(shadowOf(rule.activity).lastRequestedPermission)
        assertTrue(startedActions().isEmpty())
        assertFalse(settings.permissionRequested)
    }

    // ------------------------------------------------------------------ the banner on the Updates screen

    private fun showUpdates(
        settings: FakeSettingsStore,
        session: NotificationBannerSession = NotificationBannerSession(),
        status: () -> NotificationStatus = { systemStatus() },
    ): NotificationBannerViewModel {
        val banner = NotificationBannerViewModel(settings, status, session, io = Dispatchers.Unconfined)
        val updates = UpdatesViewModel(FakeFeedRepository(listOf(feedItem(1, title = "Cached"))), { null })
        rule.setContent {
            MaterialTheme {
                UpdatesScreen(viewModel = updates, notificationBanner = { UpdatesNotificationBanner(banner) })
            }
        }
        rule.waitForIdle()
        return banner
    }

    @Test
    fun updates_shows_the_banner_when_denied_and_hides_it_when_allowed() {
        denyNotifications()
        showUpdates(FakeSettingsStore())
        waitForTag(NotificationBannerTags.BANNER)
        rule.onNodeWithTag(NotificationBannerTags.BANNER).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.settings_notifications_denied)).assertIsDisplayed()
        rule.onNodeWithTag(UpdatesTestTags.LIST).assertIsDisplayed()
        assertNull("opening Updates never prompts on its own", shadowOf(rule.activity).lastRequestedPermission)

        pauseAndResume()
        assertNull("resuming does not nag", shadowOf(rule.activity).lastRequestedPermission)
        assertTrue("no settings screen opened on its own", startedActions().isEmpty())

        grantNotifications() // allowed in the system settings, back to the app
        pauseAndResume()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(NotificationBannerTags.BANNER).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun updates_has_no_banner_when_notifications_are_on() {
        grantNotifications()
        showUpdates(FakeSettingsStore())
        rule.waitForIdle()
        assertTrue(reads >= 1)
        rule.onAllNodesWithTag(NotificationBannerTags.BANNER).assertCountEqualsZero()
        rule.onAllNodesWithTag(NotificationBannerTags.CHANNEL_BLOCKED).assertCountEqualsZero()
    }

    @Test
    fun updates_banner_for_notifications_turned_off_opens_settings() {
        showUpdates(FakeSettingsStore()) { NotificationStatus(true, true, false, false) }
        waitForTag(NotificationBannerTags.BANNER)
        rule.onNodeWithText(string(R.string.settings_notifications_off)).assertIsDisplayed()
        rule.onNodeWithTag(NotificationBannerTags.BUTTON)
            .assertTextEquals(string(R.string.settings_notifications_open_settings)).performClick()
        rule.waitUntil(5_000) { shadowOf(rule.activity).peekNextStartedActivity() != null }
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(rule.activity).nextStartedActivity.action)
        assertNull(shadowOf(rule.activity).lastRequestedPermission)
    }

    @Test
    fun updates_banner_button_requests_once_then_opens_settings() {
        denyNotifications()
        val settings = FakeSettingsStore()
        showUpdates(settings)
        waitForTag(NotificationBannerTags.BUTTON)
        rule.onNodeWithTag(NotificationBannerTags.BUTTON)
            .assertTextEquals(string(R.string.settings_notifications_allow)).performClick()
        rule.waitUntil(5_000) { shadowOf(rule.activity).lastRequestedPermission != null }
        val request = shadowOf(rule.activity).lastRequestedPermission
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in request.requestedPermissions)
        assertTrue(settings.permissionRequested)
        assertEquals(listOf(REQUEST_PERMISSIONS_ACTION), startedActions())

        rule.runOnUiThread {
            @Suppress("DEPRECATION")
            rule.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        }
        waitForText(string(R.string.settings_notifications_open_settings))
        rule.onNodeWithTag(NotificationBannerTags.BUTTON).performClick()
        rule.waitUntil(5_000) { shadowOf(rule.activity).peekNextStartedActivity() != null }
        val intent = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(rule.activity.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertSame("requested only once", request, shadowOf(rule.activity).lastRequestedPermission)
    }

    @Test
    fun updates_banner_after_the_pairing_prompt_opens_settings_without_asking_again() {
        denyNotifications()
        val settings = FakeSettingsStore().apply { permissionRequested = true } // asked right after pairing
        showUpdates(settings)
        waitForTag(NotificationBannerTags.BUTTON)
        rule.onNodeWithTag(NotificationBannerTags.BUTTON)
            .assertTextEquals(string(R.string.settings_notifications_open_settings)).performClick()
        rule.waitUntil(5_000) { shadowOf(rule.activity).peekNextStartedActivity() != null }
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(rule.activity).nextStartedActivity.action)
        assertNull(shadowOf(rule.activity).lastRequestedPermission)
    }

    @Test
    fun updates_banner_dismiss_lasts_for_the_session() {
        denyNotifications()
        val session = NotificationBannerSession()
        showUpdates(FakeSettingsStore(), session)
        waitForTag(NotificationBannerTags.DISMISS)
        rule.onNodeWithTag(NotificationBannerTags.DISMISS).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(NotificationBannerTags.BANNER).fetchSemanticsNodes().isEmpty() }
        assertTrue(session.dismissed.value)

        pauseAndResume()
        rule.onAllNodesWithTag(NotificationBannerTags.BANNER).assertCountEqualsZero()
        assertNull("dismissing never prompts", shadowOf(rule.activity).lastRequestedPermission)
    }

    @Test
    fun updates_banner_reads_the_state_once_per_resume() {
        grantNotifications()
        showUpdates(FakeSettingsStore())
        assertEquals("one read when the screen resumes", 1, reads)
        rule.mainClock.advanceTimeBy(30 * 60_000L)
        rule.waitForIdle()
        assertEquals("nothing polls", 1, reads)
        pauseAndResume()
        assertEquals(2, reads)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() =
        assertEquals(0, fetchSemanticsNodes().size)

    private companion object {
        const val REQUEST_PERMISSIONS_ACTION = "android.content.pm.action.REQUEST_PERMISSIONS"
    }
}
