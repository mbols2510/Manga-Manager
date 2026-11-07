package com.mbols2510.mangamanager.ui.doubles

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Test tags of the Doubles screen. */
object DoublesTestTags {
    const val CONFIRM_DIALOG = "doubles_confirm_dialog"
    const val CONFIRM = "doubles_confirm"
    const val CANCEL = "doubles_cancel"
    const val SERIES_LIST = "doubles_series_list"
    const val TRASH_LIST = "doubles_trash_list"
    const val MESSAGE = "doubles_message"
}

/**
 * Doubles (spec 4.2). No covers are loaded here (series names only), so nothing sensitive is shown beyond the names
 * the server lists; every server string is rendered as plain text.
 */
/** STUB (red commit). */
@Composable
fun DoublesScreen(viewModel: DoublesViewModel, modifier: Modifier = Modifier) {
    Box(modifier)
}
