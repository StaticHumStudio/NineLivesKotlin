package com.ninelivesaudio.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent

/**
 * Displays an audiobook cover image, falling back to a vintage worn-book
 * placeholder when no cover URL is available OR when the image fails to load.
 *
 * Uses SubcomposeAsyncImage so that broken/missing server URLs also
 * show the vintage placeholder instead of a blank rectangle.
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
) {
    val seed = bookId?.hashCode() ?: title?.hashCode() ?: 0

    if (!coverUrl.isNullOrEmpty()) {
        SubcomposeAsyncImage(
            model = imageModel ?: coverUrl,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            alignment = alignment,
            loading = {
                VintageBookPlaceholder(
                    title = title,
                    seed = seed,
                )
            },
            error = {
                VintageBookPlaceholder(
                    title = title,
                    seed = seed,
                )
            },
            success = {
                SubcomposeAsyncImageContent()
            },
        )
    } else {
        VintageBookPlaceholder(
            modifier = modifier,
            title = title,
            seed = seed,
        )
    }
}
