package com.mbols2510.mangamanager.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.request.ImageRequest

/** Test tags of covers. */
object CoverTags {
    const val COVER = "cover"
    const val COVER_BLURRED = "cover_blurred"
}

/** Edge, in pixels, a sensitive cover is decoded at. */
const val SENSITIVE_DECODE_PX = 12

// TDD stub (Task 10).
fun coverRequest(context: Context, url: String, sensitive: Boolean): ImageRequest =
    ImageRequest.Builder(context).data(url).build()

// TDD stub (Task 10).
@Composable
fun CoverImage(url: String?, sensitive: Boolean, title: String, modifier: Modifier = Modifier) {
    Box(modifier)
}
