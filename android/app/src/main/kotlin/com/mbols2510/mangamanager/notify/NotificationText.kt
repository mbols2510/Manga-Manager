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

    fun summary(resources: Resources, items: List<UpdateEntity>): Summary {
        val unique = items.distinctBy { it.id }.sortedByDescending { it.id } // newest first
        val separator = resources.getString(R.string.notification_list_separator)

        val chapters = unique.sumOf { it.chapters.size }
        val series = unique.count { it.isNew }
        val counts = buildList {
            if (chapters > 0) add(resources.getQuantityString(R.plurals.notification_new_chapters, chapters, chapters))
            if (series > 0) add(resources.getQuantityString(R.plurals.notification_new_series, series, series))
        }
        val privateTitle = if (counts.isEmpty()) {
            resources.getQuantityString(R.plurals.notification_updates, unique.size, unique.size)
        } else {
            counts.joinToString(separator)
        }

        // Only non-sensitive titles are ever listed; sensitive items are only counted in "and N more".
        val titled = unique.filterNot { it.sensitive }.map { it to cleanTitle(it.title) }.filter { it.second.isNotEmpty() }
        val listed = titled.map { it.second }.distinct().take(MAX_TITLES)
        val covered = titled.count { it.second in listed }
        val more = unique.size - covered
        val privateText = when {
            listed.isEmpty() -> resources.getString(R.string.notification_hidden_titles)
            more == 0 -> listed.joinToString(separator)
            else -> resources.getString(
                R.string.notification_titles_and_more,
                listed.joinToString(separator),
                resources.getQuantityString(R.plurals.notification_and_more, more, more),
            )
        }

        return Summary(
            publicTitle = resources.getString(R.string.notification_public_title),
            publicText = resources.getString(R.string.notification_public_text),
            privateTitle = privateTitle,
            privateText = privateText,
            allSensitive = unique.isNotEmpty() && unique.all { it.sensitive },
        )
    }

    /** One line of plain text: no control or bidi characters, no lone surrogates, collapsed spaces, at most 100 characters. */
    fun cleanTitle(raw: String): String {
        val out = StringBuilder(raw.length)
        var pendingSpace = false
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            when {
                // A surrogate code point here is unpaired (a valid pair is read as one supplementary code point).
                Character.getType(cp) == Character.SURROGATE.toInt() -> Unit
                cp in BIDI_CONTROLS -> Unit
                Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp) ->
                    pendingSpace = out.isNotEmpty()
                else -> {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.appendCodePoint(cp)
                }
            }
        }
        return truncate(out.toString())
    }

    /** Cuts [text] to [MAX_TITLE_LENGTH] grapheme clusters (the ellipsis counts as one), only at cluster boundaries. */
    private fun truncate(text: String): String {
        val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT)
        graphemes.setText(text)
        var count = 0
        var cut = 0
        var boundary = graphemes.next()
        while (boundary != BreakIterator.DONE) {
            count++
            if (count == MAX_TITLE_LENGTH - 1) cut = boundary
            if (count > MAX_TITLE_LENGTH) return text.substring(0, cut).trimEnd() + ELLIPSIS
            boundary = graphemes.next()
        }
        return text
    }
}
