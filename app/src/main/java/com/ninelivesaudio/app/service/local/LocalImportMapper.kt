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

    val titles = chapterTitles(tracks)
    var cumulativeSeconds = 0.0
    return tracks.mapIndexed { index, track ->
        val startSeconds = cumulativeSeconds
        val durationSeconds = track.duration.toDouble(DurationUnit.SECONDS)
        cumulativeSeconds += durationSeconds

        Chapter(
            id = index,
            start = startSeconds,
            end = cumulativeSeconds,
            title = titles[index],
        )
    }
}

/**
 * One chapter name per track. A track's title tag names its chapter, with the
 * file name (no extension) as the fallback. Two cases always fall back to file
 * names: a book with fewer than two tracks (its title tag is the book's title),
 * and a book where at least two tracks carry the same tag, ignoring case and
 * spaces (rippers often stamp the book's name on every file).
 */
internal fun chapterTitles(tracks: List<ScannedTrack>): List<String> {
    val fileNames = tracks.map { it.filename.substringBeforeLast(".") }
    if (tracks.size < 2) return fileNames
    val tags = tracks.map { it.title?.trim().orEmpty() }
    val tagged = tags.filter { it.isNotEmpty() }
    if (tagged.size >= 2 && tagged.map { it.lowercase() }.distinct().size == 1) return fileNames
    return tags.mapIndexed { i, tag -> tag.ifEmpty { fileNames[i] } }
}
