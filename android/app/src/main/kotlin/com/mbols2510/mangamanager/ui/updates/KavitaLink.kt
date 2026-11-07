package com.mbols2510.mangamanager.ui.updates

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Opening an item's Kavita page. The URL comes from the server and is untrusted: only an absolute `http` or
 * `https` URL with a host is ever handed to another app (never `intent:`, `javascript:`, `file:`, `content:`,
 * `market:` or anything else), as a plain `ACTION_VIEW` with no extras, so nothing of the app (least of all the
 * token) travels with it.
 */
object KavitaLink {
    /** The normalized URL to open, or null when [url] is not an absolute http(s) URL. */
    fun toIntentUriOrNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // OkHttp's parser accepts exactly the http and https schemes and requires a host.
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        return parsed.toString()
    }

    /** A browsable VIEW intent for a URL that passed [toIntentUriOrNull]. */
    fun viewIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, uri.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)

    /** Opens [url] if it is allowed. False when it is not allowed or no app can open it. */
    fun open(context: Context, url: String?): Boolean {
        val uri = toIntentUriOrNull(url) ?: return false
        val intent = viewIntent(uri)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
