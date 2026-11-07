package com.mbols2510.mangamanager.notify

import android.content.res.Resources
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.db.UpdateEntity
import java.text.BreakIterator
import java.util.Locale

/**
 * The text of the ONE notification posted per check (spec 5.1, 7.5). Pure apart from string resources.
 *
 * - The public (lock-screen) variant is always generic: no title, no count of sensitive items, no cover.
 * - The private variant lists at most [MAX_TITLES] titles, never a sensitive one; the rest is "and N more".
 * - Titles are plain text: control characters, line breaks, bidi overrides and lone surrogates are removed and each
 *   title is cut to [MAX_TITLE_LENGTH] user-perceived characters, never inside an emoji or a surrogate pair.
 */
object NotificationText {
    const val MAX_TITLES = 5
    const val MAX_TITLE_LENGTH = 100
    private const val ELLIPSIS = "…"

    /** Bidirectional formatting characters that could reorder the rest of the notification text. */
    private val BIDI_CONTROLS = setOf(0x061C, 0x200E, 0x200F) + (0x202A..0x202E) + (0x2066..0x2069)

    data class Summary(
        val publicTitle: String,
        val publicText: String,
        val privateTitle: String,
        val privateText: String,
        val allSensitive: Boolean,
    )

    // TDD stub (red commit): no behavior yet.
    @Suppress("UNUSED_PARAMETER")
    fun summary(resources: Resources, items: List<UpdateEntity>): Summary = Summary("", "", "", "", false)

    /** One line of plain text: no control or bidi characters, no lone surrogates, collapsed spaces, at most 100 characters. */
    fun cleanTitle(raw: String): String = raw
}
