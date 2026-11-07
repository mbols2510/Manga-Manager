package com.mbols2510.mangamanager.ui.updates

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.api.PairedCredentialsHolder
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.data.repo.FeedRefreshResult
import com.mbols2510.mangamanager.ui.AppNavHost
import com.mbols2510.mangamanager.ui.common.CoverTags
import kotlinx.coroutines.CompletableDeferred
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Compose UI tests on Robolectric (SDK from robolectric.properties). */
@RunWith(RobolectricTestRunner::class)
class UpdatesScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int): String = rule.activity.getString(id)

    private fun show(repo: FakeFeedRepository, credentials: PairedCredentialsHolder = PairedCredentialsHolder()): UpdatesViewModel {
        val vm = UpdatesViewModel(repo, credentials::get)
        rule.setContent { MaterialTheme { UpdatesScreen(viewModel = vm) } }
        return vm
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    // ------------------------------------------------------------------ plan tests

    @Test
    fun shows_cached_items_before_network_returns() {
        val repo = FakeFeedRepository(listOf(feedItem(1, title = "Cached Title")))
        repo.gate = CompletableDeferred() // the network never answers
        val vm = show(repo)
        waitForText("Cached Title")
        rule.onNodeWithText("Cached Title").assertIsDisplayed()
        assertTrue(vm.state.value.refreshing)
        assertEquals(1, repo.refreshes)
    }

    @Test
    fun pull_to_refresh_forces_refresh() {
        val repo = FakeFeedRepository(listOf(feedItem(2, title = "Second"), feedItem(1, title = "First")))
        val vm = show(repo)
        rule.waitUntil(5_000) { repo.refreshes == 1 && !vm.state.value.refreshing }
        waitForText("First")

        // A slow, long pull from the top of the list (a quick swipe may end as a fling before the threshold).
        rule.onNodeWithTag(UpdatesTestTags.LIST).performTouchInput {
            down(Offset(left + width / 4f, top + 2f)) // beside the (hidden) indicator
            val step = (height - 10f) / 30f
            repeat(30) {
                advanceEventTime(30)
                moveBy(Offset(0f, step))
            }
            advanceEventTime(300) // hold still: no fling velocity on release
            up()
        }

        rule.waitUntil(5_000) { repo.refreshes == 2 }
        rule.waitUntil(5_000) { !vm.state.value.refreshing }
    }

    @Test
    fun sensitive_items_blur_cover() {
        val credentials = PairedCredentialsHolder().apply { set("http://127.0.0.1:9/".toHttpUrl(), "t") }
        val repo = FakeFeedRepository(
            listOf(
                feedItem(2, title = "Secret Title", sensitive = true),
                feedItem(1, title = "Open Title"),
            ),
        )
        show(repo, credentials)
        waitForText("Open Title")

        val generic = string(R.string.updates_cover_sensitive)
        rule.onAllNodes(hasTestTag(CoverTags.COVER_BLURRED), useUnmergedTree = true).assertCountEquals(1)
        rule.onNode(hasTestTag(CoverTags.COVER_BLURRED) and hasContentDescription(generic), useUnmergedTree = true)
            .assertExists()
        rule.onNode(hasTestTag(CoverTags.COVER) and hasContentDescription("Open Title"), useUnmergedTree = true)
            .assertExists()
        // The sensitive title never becomes an accessibility label of a cover.
        rule.onAllNodes(hasContentDescription("Secret Title"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun tapping_item_opens_kavita_url_intent() {
        val good = "https://kavita.example/library/2/series/3"
        val repo = FakeFeedRepository(
            listOf(
                feedItem(3, title = "Good Link", kavitaUrl = good),
                feedItem(2, title = "Script Link", kavitaUrl = "javascript:alert(1)"),
                feedItem(1, title = "Intent Link", kavitaUrl = "intent://scan/#Intent;scheme=zxing;end"),
            ),
        )
        show(repo)
        waitForText("Good Link")
        val shadow = shadowOf(rule.activity)

        rule.onNode(hasText("Script Link", substring = true)).performClick()
        rule.onNode(hasText("Intent Link", substring = true)).performClick()
        rule.waitForIdle()
        assertNull("unsafe links do nothing", shadow.nextStartedActivity)

        val row = rule.onNode(hasText("Good Link", substring = true))
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        row.performClick()
        rule.waitForIdle()
        val intent: Intent = shadow.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(good, intent.dataString)
        assertNull("no extras, never the token", intent.extras)
        assertNull(intent.component)
        assertNull(shadow.nextStartedActivity)
    }

    @Test
    fun `401_navigates_to_pairing`() {
        val auth = AuthState()
        val repo = FakeFeedRepository(listOf(feedItem(1, title = "Before Revoke")))
        repo.next = FeedRefreshResult.Unauthorized
        repo.onRefresh = { auth.markUnauthorized() } // what the repository does on a 401
        val vm = UpdatesViewModel(repo, { null })
        rule.setContent {
            MaterialTheme {
                AppNavHost(
                    isPaired = true,
                    needsRepair = auth.needsRepair,
                    pairingScreen = { Text(PAIRING) },
                    updatesScreen = { UpdatesScreen(viewModel = vm) },
                )
            }
        }
        waitForText(PAIRING)
        rule.onNodeWithText(PAIRING).assertIsDisplayed()
        rule.onAllNodesWithText("Before Revoke").assertCountEquals(0)
        assertTrue(auth.needsRepair.value)
        assertFalse(vm.state.value.refreshing)
        assertNull(vm.state.value.error)
    }

    // ------------------------------------------------------------------ controller tests

    @Test
    fun empty_cache_shows_no_updates_yet_and_cold_offline_shows_the_offline_state() {
        val repo = FakeFeedRepository()
        val vm = show(repo)
        waitForText(string(R.string.updates_empty_title))

        repo.next = FeedRefreshResult.Offline
        vm.refresh()
        waitForText(string(R.string.updates_offline_title))
        rule.onAllNodesWithText(string(R.string.updates_empty_title)).assertCountEquals(0)
    }

    @Test
    fun offline_with_cached_items_shows_a_banner_above_the_list() {
        val repo = FakeFeedRepository(listOf(feedItem(1, title = "Kept Title")))
        repo.next = FeedRefreshResult.Offline
        show(repo)
        waitForText(string(R.string.updates_offline_banner))
        rule.onNodeWithText("Kept Title").assertIsDisplayed()
    }

    private companion object {
        const val PAIRING = "PAIRING SCREEN"
    }
}
