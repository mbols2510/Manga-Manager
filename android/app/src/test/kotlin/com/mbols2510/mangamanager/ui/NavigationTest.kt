package com.mbols2510.mangamanager.ui

import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.repo.AuthState
import org.junit.Assert.assertEquals
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

    private companion object {
        const val PAIRING = "pairing-slot"
        const val FINISH = "finish-pairing"
    }
}
