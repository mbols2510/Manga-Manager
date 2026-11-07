package com.mbols2510.mangamanager.ui.common

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.mbols2510.mangamanager.R

/** Test tags of covers. */
object CoverTags {
    const val COVER = "cover"
    const val COVER_BLURRED = "cover_blurred"
}

/**
 * Edge, in pixels, a sensitive cover is decoded at. The bitmap itself is this small (on every API level), so the
 * drawn cover is an unrecognizable smear from its very first frame, whether it comes from the network, the disk
 * cache or the memory cache. On API 31+ a RenderEffect blur is drawn on top to smooth it.
 */
const val SENSITIVE_DECODE_PX = 12

private const val SENSITIVE_KEY_EXTRA = "cover"
private const val SENSITIVE_KEY_VALUE = "sensitive"

/**
 * The Coil request of a cover. A sensitive cover is decoded at exactly [SENSITIVE_DECODE_PX] (never a larger
 * bitmap) and has its own memory-cache identity, so a sharp bitmap of the same URL is never reused for it.
 */
fun coverRequest(context: Context, url: String, sensitive: Boolean): ImageRequest {
    val builder = ImageRequest.Builder(context).data(url)
    if (sensitive) {
        builder
            .size(SENSITIVE_DECODE_PX, SENSITIVE_DECODE_PX)
            .precision(Precision.EXACT)
            .memoryCacheKey(url)
            .memoryCacheKeyExtra(SENSITIVE_KEY_EXTRA, SENSITIVE_KEY_VALUE)
    }
    return builder.build()
}

/**
 * A cover thumbnail. [url] is already resolved against the paired server (null: placeholder only, no request).
 * The image is requested only while this is composed (lazy list rows), through the app's singleton loader.
 * Sensitive covers are always blurred, and their accessibility label is generic, never the [title].
 */
@Composable
fun CoverImage(url: String?, sensitive: Boolean, title: String, modifier: Modifier = Modifier) {
    val description = if (sensitive) stringResource(R.string.updates_cover_sensitive) else title
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(if (sensitive) CoverTags.COVER_BLURRED else CoverTags.COVER)
            .semantics {
                contentDescription = description
                role = Role.Image
            },
    ) {
        if (url != null) {
            val context = LocalContext.current
            val request = remember(context, url, sensitive) { coverRequest(context, url, sensitive) }
            AsyncImage(
                model = request,
                contentDescription = null, // the box above carries the label
                modifier = Modifier
                    .matchParentSize()
                    .then(if (sensitive) Modifier.blur(8.dp, BlurredEdgeTreatment.Rectangle) else Modifier),
                contentScale = ContentScale.Crop,
                // Bilinear scaling of the tiny sensitive bitmap: a soft smear, not readable pixels.
                filterQuality = FilterQuality.Low,
            )
        }
    }
}
