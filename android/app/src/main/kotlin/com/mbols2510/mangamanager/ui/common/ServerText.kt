package com.mbols2510.mangamanager.ui.common

/** Longest free text from the server (run messages, check details, job errors) shown in the app. */
const val MAX_SERVER_TEXT_CHARS = 300

/** Lines such a text may take on screen; the rest is ellipsized. */
const val SERVER_TEXT_LINES = 3

/** [text] trimmed and cut to at most [MAX_SERVER_TEXT_CHARS] UTF-16 units (an ellipsis marks the cut), whole code points only. */
fun boundedServerText(text: String): String {
    val trimmed = text.trim()
    if (trimmed.length <= MAX_SERVER_TEXT_CHARS) return trimmed
    var cut = MAX_SERVER_TEXT_CHARS - 1
    // Never split a surrogate pair (an emoji): back off one unit when the cut lands between its halves.
    if (Character.isHighSurrogate(trimmed[cut - 1])) cut--
    return trimmed.take(cut) + "…"
}
