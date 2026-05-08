package com.ninelivesaudio.app.service.local

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.AudioFile
import com.ninelivesaudio.app.domain.model.Chapter
import kotlin.time.DurationUnit

/**
 * Maps [ScannedLocalBook] to the domain [AudioBook] model for Room import.
 *
 * Key decisions:
 * - [AudioBook.isLocal] = true
 * - [AudioBook.isDownloaded] = true (so existing offline UI affordances work)
 * - [AudioBook.localPath] = first track URI (used as primary playback reference)
 * - [AudioFile.localPath] = each track's content:// URI string
 * - Chapters are synthetic: one per track, computed from cumulative durations.
 */
fun ScannedLocalBook.toAudioBook(libraryId: String): AudioBook {
    val audioFiles = tracks.map { track ->
        AudioFile(
            id = track.id,
            ino = "",
            index = track.index,
            duration = track.duration,
            filename = track.filename,
            localPath = track.uri,
            mimeType = track.mimeType,
            size = track.size,
        )
    }

    // Build synthetic chapters: one chapter per track with cumulative timing
    val chapters = buildSyntheticChapters(tracks)

    return AudioBook(
        id = id,
        libraryId = libraryId,
        isLocal = true,
        title = title,
        author = author,
        narrator = narrator,
        description = description,
        coverPath = coverUri,
        duration = duration,
        audioFiles = audioFiles,
        chapters = chapters,
        isDownloaded = true,
        localPath = tracks.firstOrNull()?.uri,
    )
}

/**
 * Build synthetic chapters from tracks.
 * Each track becomes one chapter. Timing is cumulative.
 */
private fun buildSyntheticChapters(tracks: List<ScannedTrack>): List<Chapter> {
    if (tracks.isEmpty()) return emptyList()

    var cumulativeSeconds = 0.0
    return tracks.mapIndexed { index, track ->
        val startSeconds = cumulativeSeconds
        val durationSeconds = track.duration.toDouble(DurationUnit.SECONDS)
        cumulativeSeconds += durationSeconds

        Chapter(
            id = index,
            start = startSeconds,
            end = cumulativeSeconds,
            title = track.filename.substringBeforeLast("."),
        )
    }
}
