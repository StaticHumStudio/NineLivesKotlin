package com.ninelivesaudio.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.net.URI

/**
 * Displays an audiobook cover image, falling back to a vintage worn-book
 * placeholder when no cover URL is available OR when the image fails to load.
 *
 * Uses AsyncImage with the placeholder composed underneath it, so the
 * placeholder shows while the cover loads and stays if the server URL is
 * broken, and a long list of these does not subcompose a slot per row.
 *
 * [thumbnailWidthPx] asks the server for a smaller cover. Long lists of small
 * rows pass it, while book detail and the player leave it null and get the
 * server's default size. Saved covers on the device are never touched.
 */
@Composable
fun BookCoverImage(
    coverUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    title: String? = null,
    bookId: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.TopCenter,
    imageModel: Any? = null,
    thumbnailWidthPx: Int? = null,
) {
    val seed = bookId?.hashCode() ?: title?.hashCode() ?: 0

    if (!coverUrl.isNullOrEmpty()) {
        // Keyed on the URL, so a recycled row that now shows another book
        // starts from its placeholder again.
        var loaded by remember(coverUrl, thumbnailWidthPx) { mutableStateOf(false) }
        Box(modifier = modifier, propagateMinConstraints = true) {
            if (!loaded) {
                VintageBookPlaceholder(
                    title = title,
                    seed = seed,
                )
            }
            AsyncImage(
                model = imageModel
                    ?: thumbnailWidthPx?.let { thumbnailCoverUrl(coverUrl, it) }
                    ?: coverUrl,
                contentDescription = contentDescription,
                contentScale = contentScale,
                alignment = alignment,
                onSuccess = { loaded = true },
            )
        }
    } else {
        VintageBookPlaceholder(
            modifier = modifier,
            title = title,
            seed = seed,
        )
    }
}

/**
 * Cover width for small list rows, in pixels. A 64dp row at the densest screens
 * draws about 230px, and Audiobookshelf hands back 400px when no width is asked
 * for, so lists of thousands of books fetch roughly a third of the pixels.
 */
const val THUMBNAIL_COVER_WIDTH_PX = 240

private val SERVER_COVER_PATH = Regex(".*/api/items/[^/]+/cover")

/**
 * [url] with `width=[widthPx]` added when it is an Audiobookshelf item cover
 * (`.../api/items/{id}/cover` over http or https). Audiobookshelf resizes to
 * that width and keeps the aspect ratio. Anything else comes back unchanged:
 * file and content URIs for saved covers, other hosts' images, URLs that will
 * not parse, and URLs that already name a width.
 */
internal fun thumbnailCoverUrl(url: String, widthPx: Int): String {
    if (widthPx <= 0) return url
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return url
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return url
    if (!SERVER_COVER_PATH.matches(uri.rawPath.orEmpty())) return url
    val query = uri.rawQuery.orEmpty()
    if (query.split('&').any { it.substringBefore('=') == "width" }) return url

    val fragmentAt = url.indexOf('#')
    val base = if (fragmentAt >= 0) url.substring(0, fragmentAt) else url
    val fragment = if (fragmentAt >= 0) url.substring(fragmentAt) else ""
    val separator = when {
        !base.contains('?') -> "?"
        base.endsWith("?") || base.endsWith("&") -> ""
        else -> "&"
    }
    return "$base${separator}width=$widthPx$fragment"
}
