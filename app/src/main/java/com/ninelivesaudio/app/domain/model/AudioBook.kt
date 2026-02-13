package com.ninelivesaudio.app.domain.model

import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class AudioBook(
    val id: String = "",
    val libraryId: String? = null,
    val title: String = "",
    val author: String = "",
    val narrator: String? = null,
    val description: String? = null,
    val coverPath: String? = null,
    val duration: Duration = Duration.ZERO,
    val addedAt: Long? = null, // epoch millis
    val audioFiles: List<AudioFile> = emptyList(),
    val seriesName: String? = null,
    val seriesSequence: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val currentTime: Duration = Duration.ZERO,
    val progress: Double = 0.0, // 0-1 or 0-100 from API
    val isFinished: Boolean = false,
    val isDownloaded: Boolean = false,
    val localPath: String? = null,
) {
    /** Progress normalized to 0–100 regardless of API format. */
    val progressPercent: Double
        get() {
            // Validate progress is non-negative
            val validProgress = progress.coerceAtLeast(0.0)
            return if (validProgress <= 1.0) validProgress * 100.0 else validProgress.coerceIn(0.0, 100.0)
        }

    /** Whether this book has any progress to display. */
    val hasProgress: Boolean
        get() = progress > 0

    /** Formatted progress text for library tiles — shows chapter info if available. */
    val progressText: String
        get() {
            if (progress <= 0) return ""
            val bookPct = "${progressPercent.roundToInt()}%"
            if (chapters.isNotEmpty()) {
                val idx = getCurrentChapterIndex()
                if (idx >= 0) {
                    return "$bookPct • Ch ${idx + 1}/${chapters.size}"
                }
            }
            return bookPct
        }

    /** Find the current chapter based on currentTime. */
    fun getCurrentChapter(): Chapter? {
        if (chapters.isEmpty()) return null
        val posSeconds = currentTime.inWholeMilliseconds / 1000.0
        return chapters.firstOrNull { posSeconds >= it.start && posSeconds < it.end }
            ?: if (posSeconds >= chapters.last().end) chapters.last() else null
    }

    /** Get current chapter index (0-based), or -1 if none. */
    fun getCurrentChapterIndex(): Int {
        if (chapters.isEmpty()) return -1
        val posSeconds = currentTime.inWholeMilliseconds / 1000.0
        for (i in chapters.indices) {
            if (posSeconds >= chapters[i].start && posSeconds < chapters[i].end) {
                return i
            }
        }
        return if (posSeconds >= chapters.last().end) chapters.size - 1 else -1
    }

    /** Progress within the current chapter as 0–100. */
    val currentChapterProgressPercent: Double
        get() {
            val ch = getCurrentChapter() ?: return 0.0
            if (ch.durationSeconds <= 0) return 0.0
            val elapsed = currentTime.inWholeMilliseconds / 1000.0 - ch.start
            return (elapsed / ch.durationSeconds * 100.0).coerceIn(0.0, 100.0)
        }

    /** Formatted chapter progress text (e.g. "Ch 5/71 • 42%"). */
    val chapterProgressText: String
        get() {
            if (chapters.isEmpty() || !hasProgress) return ""
            val idx = getCurrentChapterIndex()
            if (idx < 0) return ""
            val pct = currentChapterProgressPercent.roundToInt()
            return "Ch ${idx + 1}/${chapters.size} • $pct%"
        }
}

data class AudioFile(
    val id: String = "",
    val ino: String = "", // AudioBookshelf internal reference
    val index: Int = 0,
    val duration: Duration = Duration.ZERO,
    val filename: String = "",
    val localPath: String? = null,
    val mimeType: String? = null,
    val size: Long = 0,
)

data class Chapter(
    val id: Int = 0,
    val start: Double = 0.0,  // seconds
    val end: Double = 0.0,    // seconds
    val title: String = "",
) {
    val startTime: Duration get() = start.seconds
    val endTime: Duration get() = end.seconds
    val durationSeconds: Double get() = end - start
    val duration: Duration get() = durationSeconds.seconds
}
