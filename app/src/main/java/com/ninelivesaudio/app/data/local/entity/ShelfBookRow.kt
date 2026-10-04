package com.ninelivesaudio.app.data.local.entity

import androidx.room.ColumnInfo
import com.ninelivesaudio.app.data.local.converter.decodeChapterListJson
import com.ninelivesaudio.app.data.local.converter.decodeStringListJson
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.util.toEpochMillis
import kotlin.time.Duration.Companion.seconds

/**
 * One Library shelf row: every column a shelf or a group row shows, without
 * the heavy ones. Description is most of a server row's weight (about 1.9 KB
 * a book on a big library), and the audio file and tag lists are never shown
 * on a shelf, so reading `ab.*` for 50,000 books decoded about 115 MB per
 * filter change for nothing.
 *
 * ChaptersJson stays because the row's "Ch x/y" label needs it, and it is
 * "[]" for every list-synced server book anyway.
 */
data class ShelfBookRow(
    @ColumnInfo(name = "Id") val id: String,
    @ColumnInfo(name = "LibraryId") val libraryId: String?,
    @ColumnInfo(name = "IsLocal") val isLocal: Int,
    @ColumnInfo(name = "Title") val title: String,
    @ColumnInfo(name = "Author") val author: String?,
    @ColumnInfo(name = "Narrator") val narrator: String?,
    @ColumnInfo(name = "CoverPath") val coverPath: String?,
    @ColumnInfo(name = "DurationSeconds") val durationSeconds: Double,
    @ColumnInfo(name = "AddedAt") val addedAt: String?,
    @ColumnInfo(name = "CurrentTimeSeconds") val currentTimeSeconds: Double,
    @ColumnInfo(name = "Progress") val progress: Double,
    @ColumnInfo(name = "IsFinished") val isFinished: Int,
    @ColumnInfo(name = "IsDownloaded") val isDownloaded: Int,
    @ColumnInfo(name = "LocalPath") val localPath: String?,
    @ColumnInfo(name = "LocalCoverPath") val localCoverPath: String?,
    @ColumnInfo(name = "ArchivedAt") val archivedAt: Long?,
    @ColumnInfo(name = "SeriesName") val seriesName: String?,
    @ColumnInfo(name = "SeriesSequence") val seriesSequence: String?,
    @ColumnInfo(name = "GenresJson") val genresJson: String?,
    @ColumnInfo(name = "ChaptersJson") val chaptersJson: String?,
    @ColumnInfo(name = "lastPlayedAt") val lastPlayedAt: String?,
)

/**
 * The select list for [ShelfBookRow], aliased so Room maps it by name. Keep
 * it in step with the fields above. Description, AudioFilesJson, and
 * TagsJson are left out on purpose.
 */
internal const val SHELF_BOOK_COLUMNS =
    "ab.Id AS Id, ab.LibraryId AS LibraryId, ab.IsLocal AS IsLocal, ab.Title AS Title, " +
        "ab.Author AS Author, ab.Narrator AS Narrator, ab.CoverPath AS CoverPath, " +
        "ab.DurationSeconds AS DurationSeconds, ab.AddedAt AS AddedAt, " +
        "ab.CurrentTimeSeconds AS CurrentTimeSeconds, ab.Progress AS Progress, " +
        "ab.IsFinished AS IsFinished, ab.IsDownloaded AS IsDownloaded, ab.LocalPath AS LocalPath, " +
        "ab.LocalCoverPath AS LocalCoverPath, ab.ArchivedAt AS ArchivedAt, " +
        "ab.SeriesName AS SeriesName, ab.SeriesSequence AS SeriesSequence, " +
        "ab.GenresJson AS GenresJson, ab.ChaptersJson AS ChaptersJson, " +
        "pp.UpdatedAt AS lastPlayedAt"

/**
 * A shelf book. Description, audio files, and tags are EMPTY here, not
 * unknown, so this book is for showing and navigating only. Never hand it to
 * AudioBookRepository.save or anything else that writes a row back: that
 * would wipe the stored description and track list. Code that needs the
 * whole book reads it by id (getById).
 */
fun ShelfBookRow.toShelfBook(): AudioBook = AudioBook(
    id = id,
    libraryId = libraryId,
    isLocal = isLocal == 1,
    title = title,
    author = author ?: "",
    narrator = narrator,
    description = null,
    coverPath = coverPath,
    duration = durationSeconds.seconds,
    addedAt = addedAt?.toEpochMillis(),
    audioFiles = emptyList(),
    seriesName = seriesName,
    seriesSequence = seriesSequence,
    genres = decodeStringListJson(genresJson),
    tags = emptyList(),
    chapters = decodeChapterListJson(chaptersJson),
    currentTime = currentTimeSeconds.seconds,
    progress = progress,
    isFinished = isFinished == 1,
    isDownloaded = isDownloaded == 1,
    localPath = localPath,
    localCoverPath = localCoverPath,
    archivedAt = archivedAt,
    lastPlayedAt = lastPlayedAt?.toEpochMillis(),
)
