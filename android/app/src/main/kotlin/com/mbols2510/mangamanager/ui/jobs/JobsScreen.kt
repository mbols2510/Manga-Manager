package com.mbols2510.mangamanager.ui.jobs

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Test tags of the Jobs/Health screen. */
object JobsTestTags {
    const val RUN_NOW = "jobs_run_now"
    const val RUN_MESSAGE = "jobs_run_message"
    const val UNREACHABLE = "jobs_unreachable"
}

/** STUB (red commit). */
@Composable
fun JobsScreen(viewModel: JobsViewModel, modifier: Modifier = Modifier) {
    Box(modifier)
}
