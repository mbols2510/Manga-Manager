package com.mbols2510.mangamanager.ui.updates

import android.content.Context
import android.content.Intent

// TDD stub (Task 10).
object KavitaLink {
    fun toIntentUriOrNull(url: String?): String? = null

    fun viewIntent(uri: String): Intent = Intent(Intent.ACTION_VIEW)

    fun open(context: Context, url: String?): Boolean = false
}
