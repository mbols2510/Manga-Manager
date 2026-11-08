package com.mbols2510.mangamanager.ui.doubles

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.data.repo.DefaultServerToolsRepository
import com.mbols2510.mangamanager.data.repo.ToolsApi
import com.mbols2510.mangamanager.data.repo.chapter
import com.mbols2510.mangamanager.data.repo.file
import com.mbols2510.mangamanager.data.repo.series
import com.mbols2510.mangamanager.data.repo.summaries
import com.mbols2510.mangamanager.data.repo.trashEntry
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose UI tests on Robolectric. The fake server answers at once, so every wait is on state, never on time. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class DoublesScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val api = ToolsApi()
    private val full = series(
        1, "Berserk",
        chapter(10, file(100, "kept-official.cbz", pages = 24, official = true), file(101, "other-copy.cbz", pages = 22, newest = true)),
    )

    private fun string(id: Int): String = rule.activity.getString(id)

    private fun show(): DoublesViewModel {
        val vm = DoublesViewModel(DefaultServerToolsRepository({ api }, AuthState(), io = Dispatchers.Unconfined))
        rule.setContent { MaterialTheme { DoublesScreen(viewModel = vm) } }
        return vm
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun inDialog(text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(DoublesTestTags.CONFIRM_DIALOG)))

    @Test
    fun remediate_confirmation_dialog_names_kept_and_trashed_files() {
        api.seriesList = { summaries(full) }
        api.detail = { full }
        show()
        waitForText("Berserk")
        rule.onNodeWithText("Berserk").performClick()
        val keep = string(R.string.doubles_keep_selected)
        waitForText(keep)

        rule.onNodeWithText(keep).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DoublesTestTags.CONFIRM_DIALOG).fetchSemanticsNodes().isNotEmpty() }
        // Fix round 1 (ruling 24, 2a): files are shown with their parent folder ("S/<name>").
        inDialog(rule.activity.getString(R.string.doubles_confirm_remediate_keep, "S/kept-official.cbz")).assertIsDisplayed()
        inDialog("S/other-copy.cbz").assertIsDisplayed()
        rule.onNodeWithTag(DoublesTestTags.CONFIRM).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.doubles_confirm_remediate_action)).assertIsDisplayed()
        assertEquals("the dialog alone sends nothing", 0, api.count("remediate"))

        rule.onNodeWithTag(DoublesTestTags.CANCEL).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DoublesTestTags.CONFIRM_DIALOG).fetchSemanticsNodes().isEmpty() }
        assertEquals("Cancel sends nothing", 0, api.count("remediate"))

        rule.onNodeWithText(keep).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DoublesTestTags.CONFIRM).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(DoublesTestTags.CONFIRM).performClick()
        rule.waitUntil(5_000) { api.count("remediate") == 1 }
        assertEquals(listOf("remediate:1:10:100"), api.calls.filter { it.startsWith("remediate") })
        rule.onAllNodesWithTag(DoublesTestTags.CONFIRM_DIALOG).assertCountEquals(0)
    }

    @Test
    fun restore_asks_first_and_empty_states_are_shown() {
        api.seriesList = { emptyList() }
        api.trashList = { listOf(trashEntry("t1", name = "restore-me.cbz")) }
        show()
        waitForText(string(R.string.doubles_empty_title))

        rule.onNodeWithText(string(R.string.doubles_tab_trash)).performClick()
        waitForText("S/restore-me.cbz")
        rule.onNodeWithText(string(R.string.doubles_restore)).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DoublesTestTags.CONFIRM_DIALOG).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, api.count("restore"))

        api.trashList = { emptyList() }
        rule.onNodeWithTag(DoublesTestTags.CONFIRM).performClick()
        waitForText(string(R.string.doubles_trash_empty_title))
        assertEquals(1, api.count("restore"))
        rule.onNodeWithText(string(R.string.doubles_restored)).assertIsDisplayed()
    }

    @Test
    fun confirm_dialog_and_file_rows_show_the_parent_folder() {
        val sameName = series(
            1, "Berserk",
            chapter(10, file(100, "Official/Ch 12.cbz", official = true), file(101, "Scanlation/Ch 12.cbz")),
        )
        api.seriesList = { summaries(sameName) }
        api.detail = { sameName }
        show()
        waitForText("Berserk")
        rule.onNodeWithText("Berserk").performClick()
        waitForText("Official/Ch 12.cbz")
        rule.onNodeWithText("Official/Ch 12.cbz").assertIsDisplayed()
        rule.onNodeWithText("Scanlation/Ch 12.cbz").assertIsDisplayed()

        rule.onNodeWithText(string(R.string.doubles_keep_selected)).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DoublesTestTags.CONFIRM_DIALOG).fetchSemanticsNodes().isNotEmpty() }
        inDialog(rule.activity.getString(R.string.doubles_confirm_remediate_keep, "Official/Ch 12.cbz")).assertIsDisplayed()
        inDialog("Scanlation/Ch 12.cbz").assertIsDisplayed()
        rule.onNodeWithTag(DoublesTestTags.CANCEL).performClick()
        assertEquals(0, api.count("remediate"))
    }

    @Test
    fun rescan_button_sends_the_pending_chapters() {
        api.seriesList = { summaries(full) }
        api.detail = { full }
        show()
        waitForText("Berserk")
        rule.onNodeWithText("Berserk").performClick()
        val keep = string(R.string.doubles_keep_selected)
        waitForText(keep)
        rule.onAllNodesWithTag(DoublesTestTags.RESCAN).assertCountEquals(0) // nothing pending yet

        rule.onNodeWithText(keep).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DoublesTestTags.CONFIRM).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(DoublesTestTags.CONFIRM).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DoublesTestTags.RESCAN).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("nothing is sent before the button", 0, api.calls.count { it.startsWith("kavita") })

        rule.onNodeWithTag(DoublesTestTags.RESCAN).performClick()
        rule.waitUntil(5_000) { api.calls.any { it.startsWith("kavita") } }
        assertEquals(listOf("kavita:1:lib=1:[10=24]"), api.calls.filter { it.startsWith("kavita") })
        waitForText(string(R.string.doubles_kavita_done))
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DoublesTestTags.RESCAN).fetchSemanticsNodes().isEmpty() }
    }
}
