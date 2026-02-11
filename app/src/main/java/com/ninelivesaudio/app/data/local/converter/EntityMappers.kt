package com.ninelivesaudio.app.data.local.converter

import com.ninelivesaudio.app.data.local.entity.*
import com.ninelivesaudio.app.domain.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

private val json = Json { ignoreUnknownKeys = true }

// ─── AudioBook ───────────────────────────────────────────────────────────

fun AudioBookEntity.toDomain(): AudioBook = AudioBook(
    id = id,
    libraryId = libraryId,
    title = title,
    author = author ?: "",
    narrator = narrator,
    description = description,
    coverPath = coverPath,
    duration = durationSeconds.seconds,
    addedAt = addedAt?.toEpochMillis(),
    audioFiles = audioFilesJson?.let {
        try { json.decodeFromString<List<AudioFileJson>>(it).map { af -> af.toDomain() } }
        catch (_: Exception) { emptyList() }
    } ?: emptyList(),
    seriesName = seriesName,
    seriesSequence = seriesSequence,
    genres = genresJson?.let {
        try { json.decodeFromString<List<String>>(it) }
        catch (_: Exception) { emptyList() }
    } ?: emptyList(),
    tags = tagsJson?.let {
        try { json.decodeFromString<List<String>>(it) }
        catch (_: Exception) { emptyList() }
    } ?: emptyList(),
    chapters = chaptersJson?.let {
        try { json.decodeFromString<List<ChapterJson>>(it).map { ch -> ch.toDomain() } }
        catch (_: Exception) { emptyList() }
    } ?: emptyList(),
    currentTime = currentTimeSeconds.seconds,
    progress = progress,
    isFinished = isFinished == 1,
    isDownloaded = isDownloaded == 1,
    localPath = localPath,
)

fun AudioBook.toEntity(): AudioBookEntity = AudioBookEntity(
    id = id,
    libraryId = libraryId,
    title = title,
    author = author,
    narrator = narrator,
    description = description,
    coverPath = coverPath,
    durationSeconds = duration.inWholeMilliseconds / 1000.0,
    addedAt = addedAt?.toIso8601(),
    audioFilesJson = json.encodeToString(audioFiles.map { it.toJson() }),
    currentTimeSeconds = currentTime.inWholeMilliseconds / 1000.0,
    progress = progress,
    isFinished = if (isFinished) 1 else 0,
    isDownloaded = if (isDownloaded) 1 else 0,
    localPath = localPath,
    seriesName = seriesName,
    seriesSequence = seriesSequence,
    genresJson = json.encodeToString(genres),
    tagsJson = json.encodeToString(tags),
    chaptersJson = json.encodeToString(chapters.map { it.toJson() }),
)

// ─── Library ─────────────────────────────────────────────────────────────

fun LibraryEntity.toDomain(): Library = Library(
    id = id,
    name = name,
    folders = foldersJson?.let {
        try { json.decodeFromString<List<FolderJson>>(it).map { f -> f.toDomain() } }
        catch (_: Exception) { emptyList() }
    } ?: emptyList(),
    displayOrder = displayOrder,
    icon = icon,
    mediaType = mediaType,
)

fun Library.toEntity(): LibraryEntity = LibraryEntity(
    id = id,
    name = name,
    displayOrder = displayOrder,
    icon = icon,
    mediaType = mediaType,
    foldersJson = json.encodeToString(folders.map { it.toJson() }),
)

// ─── DownloadItem ────────────────────────────────────────────────────────

fun DownloadItemEntity.toDomain(): DownloadItem = DownloadItem(
    id = id,
    audioBookId = audioBookId,
    title = title,
    status = DownloadStatus.entries.getOrElse(status) { DownloadStatus.Queued },
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    startedAt = startedAt?.toEpochMillis(),
    completedAt = completedAt?.toEpochMillis(),
    errorMessage = errorMessage,
    filesToDownload = filesToDownloadJson?.let {
        try { json.decodeFromString<List<String>>(it) }
        catch (_: Exception) { emptyList() }
    } ?: emptyList(),
)

fun DownloadItem.toEntity(): DownloadItemEntity = DownloadItemEntity(
    id = id,
    audioBookId = audioBookId,
    title = title,
    status = status.ordinal,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    startedAt = startedAt?.toIso8601(),
    completedAt = completedAt?.toIso8601(),
    errorMessage = errorMessage,
    filesToDownloadJson = json.encodeToString(filesToDownload),
)

// ─── JSON serialization helpers for nested objects ───────────────────────

@kotlinx.serialization.Serializable
internal data class AudioFileJson(
    val Id: String = "",
    val Ino: String = "",
    val Index: Int = 0,
    val Duration: Double = 0.0, // stored as seconds
    val Filename: String = "",
    val LocalPath: String? = null,
    val MimeType: String? = null,
    val Size: Long = 0,
)

internal fun AudioFileJson.toDomain(): AudioFile = AudioFile(
    id = Id,
    ino = Ino,
    index = Index,
    duration = Duration.seconds,
    filename = Filename,
    localPath = LocalPath,
    mimeType = MimeType,
    size = Size,
)

internal fun AudioFile.toJson(): AudioFileJson = AudioFileJson(
    Id = id,
    Ino = ino,
    Index = index,
    Duration = duration.inWholeMilliseconds / 1000.0,
    Filename = filename,
    LocalPath = localPath,
    MimeType = mimeType,
    Size = size,
)

@kotlinx.serialization.Serializable
internal data class ChapterJson(
    val Id: Int = 0,
    val Start: Double = 0.0,
    val End: Double = 0.0,
    val Title: String = "",
)

internal fun ChapterJson.toDomain(): Chapter = Chapter(
    id = Id,
    start = Start,
    end = End,
    title = Title,
)

internal fun Chapter.toJson(): ChapterJson = ChapterJson(
    Id = id,
    Start = start,
    End = end,
    Title = title,
)

@kotlinx.serialization.Serializable
internal data class FolderJson(
    val Id: String = "",
    val FullPath: String = "",
    val LibraryId: String = "",
)

internal fun FolderJson.toDomain(): Folder = Folder(
    id = Id,
    fullPath = FullPath,
    libraryId = LibraryId,
)

internal fun Folder.toJson(): FolderJson = FolderJson(
    Id = id,
    FullPath = fullPath,
    LibraryId = libraryId,
)

// ─── Timestamp helpers ───────────────────────────────────────────────────

private val isoFormatter = DateTimeFormatter.ISO_INSTANT

internal fun String.toEpochMillis(): Long? = try {
    Instant.from(isoFormatter.parse(this)).toEpochMilli()
} catch (_: Exception) {
    try {
        // Fallback: parse as ISO_OFFSET_DATE_TIME
        java.time.OffsetDateTime.parse(this).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

internal fun Long.toIso8601(): String =
    Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
