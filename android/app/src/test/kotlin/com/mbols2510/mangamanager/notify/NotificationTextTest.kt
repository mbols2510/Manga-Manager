package com.mbols2510.mangamanager.notify

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.mbols2510.mangamanager.R
import com.mbols2510.mangamanager.data.db.UpdateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationTextTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun summary(vararg items: UpdateEntity) = NotificationText.summary(res, items.toList())

    /** No unpaired surrogate and no control character anywhere. */
    private fun assertWellFormed(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue("high surrogate at $i is unpaired", i + 1 < text.length && Character.isLowSurrogate(text[i + 1]))
                i += 2
                continue
            }
            assertFalse("unpaired low surrogate at $i", Character.isLowSurrogate(c))
            assertFalse("control character U+%04X at $i".format(c.code), Character.isISOControl(c))
            i++
        }
    }

    private val NotificationText.Summary.all get() = listOf(publicTitle, publicText, privateTitle, privateText)

    private fun assertGenericPublic(s: NotificationText.Summary) {
        assertEquals(res.getString(R.string.notification_public_title), s.publicTitle)
        assertEquals(res.getString(R.string.notification_public_text), s.publicText)
    }

    @Test
    fun title_with_emoji_quotes_html_and_300_chars_is_truncated_safely() {
        val prefix = "<b>\"Quoted\" & 'html'</b> &amp; "
        val pad = "x".repeat(98 - prefix.length)
        // The 99th user-perceived character is an emoji (a surrogate pair): a cut by UTF-16 units would split it.
        val raw = prefix + pad + "😀".repeat(5) + "y".repeat(300)
        assertTrue(raw.length > 300)

        val clean = NotificationText.cleanTitle(raw)
        assertEquals(prefix + pad + "😀" + "…", clean)
        assertEquals(NotificationText.MAX_TITLE_LENGTH, clean.codePointCount(0, clean.length))
        assertWellFormed(clean)

        val s = summary(update(1, title = raw))
        // Rendered literally: the markup and the entity are text, not HTML.
        assertTrue(s.privateText, s.privateText.startsWith(prefix + pad))
        assertTrue(s.privateText.contains("<b>") && s.privateText.contains("&amp;"))
        s.all.forEach { assertWellFormed(it) }
        assertGenericPublic(s)
    }

    @Test
    fun empty_chapters_list_does_not_crash() {
        val one = summary(update(1, title = "Alpha", chapters = emptyList()))
        assertEquals("1 update", one.privateTitle)
        assertEquals("Alpha", one.privateText)

        val mixed = summary(
            update(1, title = "Alpha", chapters = emptyList()),
            update(2, title = "Beta", chapters = emptyList(), isNew = true),
            update(3, title = "Gamma", chapters = listOf("Ch. 1", "Ch. 2")),
        )
        assertEquals("2 new chapters, 1 new series", mixed.privateTitle)
        assertEquals("Gamma, Beta, Alpha", mixed.privateText)

        val empty = summary(update(4, title = "", chapters = emptyList()))
        assertEquals("1 update", empty.privateTitle)
        assertEquals(res.getString(R.string.notification_hidden_titles), empty.privateText)
    }

    @Test
    fun mixed_group_with_any_sensitive_item_hides_all_titles_in_public_version() {
        val s = summary(
            update(1, title = "Alpha"),
            update(2, title = "Secret Title", sensitive = true),
        )
        assertGenericPublic(s)
        for (part in listOf(s.publicTitle, s.publicText)) {
            assertFalse(part, part.contains("Alpha"))
            assertFalse(part, part.contains("Secret"))
        }
        assertFalse("a sensitive title is never listed", s.privateText.contains("Secret"))
        assertEquals("Alpha and 1 more", s.privateText)
        assertFalse(s.allSensitive)

        val onlySensitive = summary(update(3, title = "Secret Title", sensitive = true))
        assertTrue(onlySensitive.allSensitive)
        onlySensitive.all.forEach { assertFalse(it, it.contains("Secret")) }
        assertEquals(res.getString(R.string.notification_hidden_titles), onlySensitive.privateText)
    }

    @Test
    fun lone_surrogate_or_control_characters_are_stripped() {
        val raw = "A\uD800B\nC\tD\u0000E‮F\r\nG\uDC00⁦  "
        assertEquals("AB C D EF G", NotificationText.cleanTitle(raw))
        val s = summary(update(1, title = raw))
        assertEquals("AB C D EF G", s.privateText)
        s.all.forEach { assertWellFormed(it) }
    }

    @Test
    fun more_than_five_titles_collapse_to_and_n_more() {
        val s = NotificationText.summary(res, (1L..7L).map { update(it, title = "T$it") })
        assertEquals("T7, T6, T5, T4, T3 and 2 more", s.privateText)
        assertEquals("7 new chapters", s.privateTitle)

        // The same series twice is listed once; it still counts as covered.
        val dup = summary(update(1, title = "Same"), update(2, title = "Same"), update(3, title = "Other"))
        assertEquals("Other, Same", dup.privateText)
    }

    @Test
    fun counts_use_plurals() {
        assertEquals("1 new chapter", summary(update(1)).privateTitle)
        assertEquals("1 new chapter, 2 new series",
            summary(update(1), update(2, isNew = true, chapters = emptyList()), update(3, isNew = true, chapters = emptyList())).privateTitle)
    }
}
