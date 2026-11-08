package com.mbols2510.mangamanager.ui

import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.repo.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavigationTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val auth = AuthState()

    private fun show(isPaired: Boolean) {
        rule.setContent {
            MaterialTheme {
                AppNavHost(
                    isPaired = isPaired,
                    needsRepair = auth.needsRepair,
                    pairingScreen = { onPaired ->
                        Text(PAIRING)
                        Button(onClick = onPaired) { Text(FINISH) }
                    },
                )
            }
        }
    }

    private fun assertOnPairing() {
        rule.onNodeWithText(PAIRING).assertIsDisplayed()
        rule.onAllNodesWithText(context.getString(R.string.nav_settings)).assertCountEquals(0) // no bottom bar
    }

    @Test
    fun start_route_depends_on_pairing_and_repair() {
        assertEquals(Routes.PAIRING, startRoute(isPaired = false, needsRepair = false))
        assertEquals(Routes.PAIRING, startRoute(isPaired = true, needsRepair = true))
        assertEquals(Routes.UPDATES, startRoute(isPaired = true, needsRepair = false))
    }

    @Test
    fun unpaired_app_starts_on_pairing_and_lands_on_updates_after_pairing() {
        show(isPaired = false)
        assertOnPairing()
        rule.onNodeWithText(FINISH).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.coming_soon)).assertIsDisplayed()
        rule.onAllNodesWithText(PAIRING).assertCountEquals(0)
    }

    @Test
    fun needs_repair_forces_pairing_route() {
        show(isPaired = true)
        rule.onNodeWithText(context.getString(R.string.coming_soon)).assertIsDisplayed()
        rule.onAllNodesWithText(PAIRING).assertCountEquals(0)
        // Bottom bar is visible while paired; move away from the start destination.
        rule.onNodeWithText(context.getString(R.string.nav_settings)).performClick()
        rule.waitForIdle()

        auth.markUnauthorized() // a 401 anywhere while the app is open
        rule.waitForIdle()
        assertOnPairing()

        // A successful re-pair clears needsRepair and lands on updates.
        auth.clear()
        rule.onNodeWithText(FINISH).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.coming_soon)).assertIsDisplayed()
        rule.onAllNodesWithText(PAIRING).assertCountEquals(0)
        rule.onAllNodesWithText(context.getString(R.string.nav_updates)).assertCountEquals(2) // title + bar item
    }

    /** Stands in for a per-screen ViewModel; [pendingScans] plays the Doubles screen's pending Kavita scans. */
    class ProbeViewModel : ViewModel() {
        val pendingScans = mutableMapOf<Long, String>()
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    @Test
    fun viewmodels_are_recreated_after_unpair() {
        val made = mutableMapOf<String, MutableList<ProbeViewModel>>()

        @Composable
        fun probe(screen: String) {
            val vm = viewModel<ProbeViewModel>()
            val list = made.getOrPut(screen) { mutableListOf() }
            if (list.none { it === vm }) list += vm
            Text("$screen-screen")
        }

        rule.setContent {
            MaterialTheme {
                AppNavHost(
                    isPaired = true,
                    needsRepair = auth.needsRepair,
                    pairingScreen = { onPaired ->
                        Text(PAIRING)
                        Button(onClick = onPaired) { Text(FINISH) }
                    },
                    updatesScreen = { probe("updates") },
                    doublesScreen = { probe("doubles") },
                    jobsScreen = { probe("jobs") },
                    settingsScreen = { toPairing ->
                        probe("settings")
                        Button(onClick = toPairing) { Text(UNPAIR) }
                    },
                )
            }
        }
        fun tab(label: Int, screen: String) {
            rule.onNode(hasText(context.getString(label)) and hasClickAction()).performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithText("$screen-screen").fetchSemanticsNodes().isNotEmpty() }
        }

        // First pairing: visit every tab (switching tabs saves their state) and leave a pending scan behind.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("updates-screen").fetchSemanticsNodes().isNotEmpty() }
        tab(R.string.nav_doubles, "doubles")
        made.getValue("doubles").single().pendingScans[7L] = "chapters of the old server"
        tab(R.string.nav_jobs, "jobs")
        tab(R.string.nav_settings, "settings")
        val before = made.mapValues { it.value.single() }
        assertEquals(setOf("updates", "doubles", "jobs", "settings"), before.keys)

        rule.onNodeWithText(UNPAIR).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(PAIRING).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        before.forEach { (screen, vm) -> assertTrue("$screen ViewModel cleared by the unpair", vm.cleared) }

        // Next pairing: every screen gets a new ViewModel.
        rule.onNodeWithText(FINISH).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("updates-screen").fetchSemanticsNodes().isNotEmpty() }
        tab(R.string.nav_doubles, "doubles")
        tab(R.string.nav_jobs, "jobs")
        tab(R.string.nav_settings, "settings")
        for (screen in before.keys) {
            val after = made.getValue(screen).last()
            assertTrue("$screen got a new ViewModel after the next pairing", after !== before.getValue(screen))
        }
        assertTrue("no pending scan of the old pairing", made.getValue("doubles").last().pendingScans.isEmpty())
    }

    private companion object {
        const val UNPAIR = "unpair-now"
        const val PAIRING = "pairing-slot"
        const val FINISH = "finish-pairing"
    }
}
