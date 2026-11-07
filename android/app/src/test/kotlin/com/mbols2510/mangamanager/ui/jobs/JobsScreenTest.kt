package com.mbols2510.mangamanager.ui.jobs

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.api.HealthCheckDto
import com.mbols2510.mangamanager.data.api.HealthDto
import com.mbols2510.mangamanager.data.api.RunDto
import com.mbols2510.mangamanager.data.api.StatusDto
import com.mbols2510.mangamanager.data.repo.AuthState
import com.mbols2510.mangamanager.data.repo.DefaultServerToolsRepository
import com.mbols2510.mangamanager.data.repo.ToolsApi
import com.mbols2510.mangamanager.data.repo.errorResponse
import com.mbols2510.mangamanager.data.repo.successResponse
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose UI tests on Robolectric. The fake server answers at once; waits are on state, never on time. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class JobsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val api = ToolsApi()

    private fun string(id: Int): String = rule.activity.getString(id)

    private fun show() {
        // No delayed status re-read in the UI test (the ViewModel test covers it).
        val vm = JobsViewModel(DefaultServerToolsRepository({ api }, AuthState(), io = Dispatchers.Unconfined), statusRecheckDelayMs = 0)
        rule.setContent { MaterialTheme { JobsScreen(viewModel = vm) } }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun run_now_messages_are_distinct_on_screen() {
        api.statusHandler = { StatusDto(updates = 2) }
        api.healthHandler = { HealthDto(checks = listOf(HealthCheckDto("Database", "ok", "connected", 3))) }
        show()
        waitForText("Database")

        val cases = listOf(
            { successResponse(202, RunDto(true, "Run started")) } to R.string.jobs_run_started,
            { errorResponse<RunDto>(409, """{"started":false}""") } to R.string.jobs_run_already_running,
            { errorResponse<RunDto>(403, "<h1>Forbidden</h1>") } to R.string.jobs_run_disabled,
        )
        val shown = mutableListOf<String>()
        cases.forEachIndexed { i, (answer, message) ->
            api.runHandler = answer
            rule.onNodeWithTag(JobsTestTags.RUN_NOW).assertIsEnabled().performClick()
            rule.waitUntil(5_000) { api.count("run") == i + 1 }
            val text = string(message)
            waitForText(text)
            rule.onNodeWithText(text).assertIsDisplayed()
            shown += text
        }
        assertEquals(3, shown.toSet().size)
    }

    @Test
    fun health_failure_shows_unreachable_with_retry() {
        api.statusHandler = { throw IOException("no route to host") }
        show()
        waitForText(string(R.string.unreachable_title))
        rule.onNodeWithTag(JobsTestTags.UNREACHABLE).assertIsDisplayed()

        api.statusHandler = { StatusDto() }
        api.healthHandler = { HealthDto(checks = listOf(HealthCheckDto("Kavita", "fail", "HTTP 502", 10))) }
        rule.onNodeWithText(string(R.string.action_retry)).performClick()
        waitForText("Kavita")
        rule.onNodeWithText(string(R.string.jobs_check_fail)).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText(string(R.string.unreachable_title)).fetchSemanticsNodes().size)
    }
}
