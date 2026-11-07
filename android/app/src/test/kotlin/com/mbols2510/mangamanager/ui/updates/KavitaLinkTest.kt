package com.mbols2510.mangamanager.ui.updates

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KavitaLinkTest {
    @Test
    fun kavita_scheme_allowlist_http_https_only() {
        assertEquals(
            "https://kavita.example/library/2/series/3",
            KavitaLink.toIntentUriOrNull("https://kavita.example/library/2/series/3"),
        )
        assertEquals("http://192.168.1.5:5000/library/1/series/9", KavitaLink.toIntentUriOrNull("http://192.168.1.5:5000/library/1/series/9"))
        assertEquals("scheme case is normalized", "https://kavita.example/x", KavitaLink.toIntentUriOrNull("HTTPS://kavita.example/x"))

        val rejected = listOf(
            null,
            "",
            "   ",
            "javascript:alert(1)",
            "JavaScript:alert(document.cookie)",
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "intent:#Intent;action=android.intent.action.VIEW;end",
            "file:///data/data/com.mbols2510.mangamanager/shared_prefs/x.xml",
            "content://com.example.provider/secret",
            "market://details?id=com.example",
            "data:text/html,<script>alert(1)</script>",
            "ftp://kavita.example/file",
            "mailto:someone@example.com",
            "tel:123",
            "//kavita.example/library/2",
            "/library/2/series/3",
            "kavita.example/library/2",
            "https://",
            "http:/",
        )
        for (url in rejected) assertNull("must reject <$url>", KavitaLink.toIntentUriOrNull(url))
    }

    @Test
    fun view_intent_carries_only_the_url() {
        val intent = KavitaLink.viewIntent("https://kavita.example/library/2/series/3")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://kavita.example/library/2/series/3", intent.dataString)
        assertNull(intent.extras)
        assertNull(intent.component)
        assertNull(intent.`package`)
    }
}
