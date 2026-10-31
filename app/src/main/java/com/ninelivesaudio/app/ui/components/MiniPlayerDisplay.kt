package com.ninelivesaudio.app.ui.components

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Chapter
import com.ninelivesaudio.app.service.PlaybackState
import kotlin.time.Duration

internal data class MiniPlayerDisplay(
    val book: AudioBook,
    val progress: Float,
    val chapterTitle: String?,
    val loading: Boolean,
)

/**
 * What the mini player shows. A book that is loading shows its own title and
 * cover with an empty bar, no chapter line and Play disabled, since the
 * position and chapter on hand still belong to the previous book (#42). If the
 * load fails or is abandoned, the previous book shows again.
 */
internal fun miniPlayerDisplay(
    current: AudioBook?,
    loading: AudioBook?,
    state: PlaybackState,
    position: Duration,
    duration: Duration,
    chapter: Chapter?,
): MiniPlayerDisplay? {
    if (loading != null) return MiniPlayerDisplay(loading, progress = 0f, chapterTitle = null, loading = true)
    val book = current ?: return null
    if (state == PlaybackState.STOPPED && book.title.isEmpty()) return null
    val progress = if (duration > Duration.ZERO) {
        (position.inWholeMilliseconds.toFloat() / duration.inWholeMilliseconds.toFloat()).coerceIn(0f, 1f)
    } else 0f
    return MiniPlayerDisplay(book, progress, chapter?.title, loading = false)
}
