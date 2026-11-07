package com.mbols2510.mangamanager.ui.updates

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Test tags of the Updates screen. */
object UpdatesTestTags {
    const val LIST = "updates_list"
}

// TDD stub (Task 10).
@Composable
fun UpdatesScreen(viewModel: UpdatesViewModel, modifier: Modifier = Modifier) {
    Box(modifier)
}
