package com.ninelivesaudio.app.data.local.converter

import com.ninelivesaudio.app.data.local.entity.*
import com.ninelivesaudio.app.domain.model.*
import com.ninelivesaudio.app.domain.util.toEpochMillis
import com.ninelivesaudio.app.domain.util.toIso8601
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

private val json = Json { ignoreUnknownKeys = true }

private inline fun <reified T> decodeJsonList(jsonString: String?): List<T> =
    jsonString?.let {
        try { json.decodeFromString<List<T>>(it) }
        catch (_: Exception) { emptyList() }
    } ?: emptyList()

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
    audioFiles = decodeJsonList<AudioFileJson>(audioFilesJson).map { it.toDomain() },
    seriesName = seriesName,
    seriesSequence = seriesSequence,
    genres = decodeJsonList<String>(genresJson),
    tags = decodeJsonList<String>(tagsJson),
    chapters = decodeJsonList<ChapterJson>(chaptersJson).map { it.toDomain() },
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
    durationSeconds = duration.toDouble(kotlin.time.DurationUnit.SECONDS),
    addedAt = addedAt?.toIso8601(),
    audioFilesJson = json.encodeToString(audioFiles.map { it.toJson() }),
    currentTimeSeconds = currentTime.toDouble(kotlin.time.DurationUnit.SECONDS),
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
    folders = decodeJsonList<FolderJson>(foldersJson).map { it.toDomain() },
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
    filesToDownload = decodeJsonList<String>(filesToDownloadJson),
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
    Duration = duration.toDouble(kotlin.time.DurationUnit.SECONDS),
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

