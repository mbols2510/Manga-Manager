package com.mbols2510.mangamanager.ui.pairing

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.FakeSettingsStore
import com.mbols2510.mangamanager.data.repo.AuthState
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Compose UI tests on Robolectric (SDK from robolectric.properties). */
@RunWith(RobolectricTestRunner::class)
class PairingScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tokens = FakeTokenStore()
    private val server = FakeServer(validToken = "valid-token")

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun show() {
        val vm = PairingViewModel(
            tokens, FakeSettingsStore(), AuthState(), FakeUpdatesRepository(), server, Dispatchers.Unconfined,
        )
        rule.setContent { MaterialTheme { PairingScreen(viewModel = vm, onPaired = {}) } }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun enterAndSubmit(code: String) {
        rule.onNodeWithText(string(R.string.pairing_code_label)).performTextInput(code)
        rule.onNode(hasText(string(R.string.pairing_submit)) and hasClickAction()).performClick()
    }

    @Test
    fun pairing_screen_shows_paste_field_and_error() {
        show()
        rule.onNodeWithText(string(R.string.pairing_title)).assertIsDisplayed()
        // The field is labelled and accepts text.
        rule.onNode(hasSetTextAction()).assertIsDisplayed()

        enterAndSubmit("definitely not a code")

        val error = string(R.string.pairing_error_invalid_code)
        waitForText(error)
        rule.onNodeWithText(error).assertIsDisplayed()
        val live = rule.onNodeWithText(error).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        assertEquals("error is announced", androidx.compose.ui.semantics.LiveRegionMode.Polite, live)
        val field = rule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertEquals("pasted text is cleared", "", field.config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertNull(tokens.token)
    }

    @Test
    fun ime_done_action_submits_the_code_only_when_submit_is_enabled() {
        show()
        val error = string(R.string.pairing_error_invalid_code)

        rule.onNode(hasSetTextAction()).performImeAction() // empty field: the button is disabled, so nothing happens
        rule.waitForIdle()
        rule.onAllNodesWithText(error).assertCountEquals(0)

        rule.onNode(hasSetTextAction()).performTextInput("definitely not a code")
        rule.onNode(hasSetTextAction()).performImeAction()
        waitForText(error)
        val field = rule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertEquals("code cleared after Done", "", field.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    @Test
    fun auth_disabled_confirmation_dialog_shows_only_on_private_host() {
        server.authDisabled = true
        show()
        val confirm = string(R.string.pairing_auth_disabled_confirm)

        enterAndSubmit(pairingCode("https://manga.example.com", "valid-token"))
        waitForText(string(R.string.pairing_error_auth_disabled))
        rule.onAllNodesWithText(confirm).assertCountEquals(0)

        enterAndSubmit(pairingCode("http://192.168.1.5:5000", "valid-token"))
        waitForText(confirm)
        rule.onNodeWithText(string(R.string.pairing_auth_disabled_message, "192.168.1.5")).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.pairing_auth_disabled_cancel)).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(confirm).fetchSemanticsNodes().isEmpty() }
        rule.onAllNodesWithText(confirm).assertCountEquals(0)
        assertNull("declining stores nothing", tokens.token)
    }
}
