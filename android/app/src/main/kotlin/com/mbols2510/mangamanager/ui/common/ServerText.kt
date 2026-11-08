package com.mbols2510.mangamanager.ui.common

/** Longest free text from the server (run messages, check details, job errors) shown in the app. */
const val MAX_SERVER_TEXT_CHARS = 300

/** Lines such a text may take on screen; the rest is ellipsized. */
const val SERVER_TEXT_LINES = 3

/** [text] trimmed and cut to [MAX_SERVER_TEXT_CHARS] characters (an ellipsis marks the cut). */
fun boundedServerText(text: String): String {
    val trimmed = text.trim()
    return if (trimmed.length <= MAX_SERVER_TEXT_CHARS) trimmed else trimmed.take(MAX_SERVER_TEXT_CHARS - 1) + "…"
}
