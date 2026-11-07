package com.mbols2510.mangamanager.ui.updates

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
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
import androidx.compose.ui.unit.dp
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
import org.robolectric.annotation.Config

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
    @Config(qualifiers = "w411dp-h891dp") // a phone-sized screen, so the list is tall enough to pull
    fun pull_to_refresh_forces_refresh() {
        val repo = FakeFeedRepository(listOf(feedItem(2, title = "Second"), feedItem(1, title = "First")))
        val vm = UpdatesViewModel(repo, { null })
        // Records what the pull-to-refresh container leaves unconsumed (diagnostics for the assertion message).
        val seen = mutableListOf<String>()
        val outer = object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                seen += "pre(${available.y.toInt()},$source)"
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                seen += "post(${consumed.y.toInt()},${available.y.toInt()})"
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                seen += "fling(${available.y.toInt()})"
                return Velocity.Zero
            }
        }
        rule.setContent { MaterialTheme { Box(Modifier.nestedScroll(outer)) { UpdatesScreen(viewModel = vm) } } }
        rule.waitUntil(5_000) { repo.refreshes == 1 && !vm.state.value.refreshing }
        waitForText("First")
        // Let the indicator's hide animation (started when the first refresh ended) finish: while it runs, the
        // pull-to-refresh node ignores scroll deltas.
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()

        // A slow pull of 400 dp from the top of the list (0.5 drag multiplier: 200 dp, beyond the 80 dp threshold).
        rule.onNodeWithTag(UpdatesTestTags.LIST).performTouchInput {
            down(Offset(left + width / 4f, top + 2f)) // beside the (hidden) indicator
            val step = 400.dp.toPx() / 40f
            repeat(40) {
                advanceEventTime(20)
                moveBy(Offset(0f, step))
            }
            advanceEventTime(300) // hold still: no fling velocity on release
            up()
        }

        rule.waitForIdle()
        assertEquals("refreshes after the pull; nested scroll seen above the container: ${seen.take(12)} … ${seen.takeLast(4)} (${seen.size})", 2, repo.refreshes)
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
