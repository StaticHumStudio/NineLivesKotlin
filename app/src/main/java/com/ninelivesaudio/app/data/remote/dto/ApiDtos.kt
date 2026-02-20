package com.ninelivesaudio.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Login ───────────────────────────────────────────────────────────────

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val user: ApiUser? = null,
)

@Serializable
data class ApiUser(
    val id: String? = null,
    val username: String? = null,
    val token: String? = null,
)

// ─── Libraries ───────────────────────────────────────────────────────────

@Serializable
data class LibrariesResponse(
    val libraries: List<ApiLibrary> = emptyList(),
)

@Serializable
data class ApiLibrary(
    val id: String = "",
    val name: String = "",
    val displayOrder: Int = 0,
    val icon: String? = null,
    val mediaType: String? = null,
    val folders: List<ApiFolder>? = null,
)

@Serializable
data class ApiFolder(
    val id: String = "",
    val fullPath: String = "",
)

// ─── Library Items ───────────────────────────────────────────────────────

@Serializable
data class LibraryItemsResponse(
    val results: List<ApiLibraryItem> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
)

@Serializable
data class ApiLibraryItem(
    val id: String = "",
    val libraryId: String? = null,
    val addedAt: Long? = null,
    val media: ApiMedia? = null,
    val userMediaProgress: ApiUserMediaProgress? = null,
)

@Serializable
data class ApiMedia(
    val metadata: ApiMetadata? = null,
    val coverPath: String? = null,
    val duration: Double? = null,
    val audioFiles: List<ApiAudioFile>? = null,
    val chapters: List<ApiChapter>? = null,
)

@Serializable
data class ApiMetadata(
    val title: String? = null,
    val authorName: String? = null,
    val narratorName: String? = null,
    val description: String? = null,
    val authors: List<ApiAuthor>? = null,
    val narrators: List<String>? = null,
    val seriesName: String? = null,
    val series: ApiSeriesField? = null,
    val genres: List<String>? = null,
    val tags: List<String>? = null,
)

@Serializable
data class ApiAuthor(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class ApiSeries(
    val id: String? = null,
    val name: String? = null,
    val sequence: String? = null,
)

@Serializable
data class ApiAudioFile(
    val ino: String? = null,
    val index: Int? = null,
    val duration: Double? = null,
    val mimeType: String? = null,
    val metadata: ApiFileMetadata? = null,
)

@Serializable
data class ApiFileMetadata(
    val filename: String? = null,
    val size: Long? = null,
)

@Serializable
data class ApiUserMediaProgress(
    val currentTime: Double? = null,
    val progress: Double? = null,
    val isFinished: Boolean? = null,
)

@Serializable
data class ApiChapter(
    val id: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val title: String = "",
)

// ─── Playback Session ────────────────────────────────────────────────────

@Serializable
data class StartPlaybackRequest(
    val deviceInfo: DeviceInfo,
    val supportedMimeTypes: List<String> = listOf(
        "audio/mpeg", "audio/mp4", "audio/ogg", "audio/flac"
    ),
)

@Serializable
data class DeviceInfo(
    val clientName: String = "NineLivesAudio",
    val deviceId: String = "",
)

@Serializable
data class ApiPlaybackSession(
    val id: String = "",
    val libraryItemId: String = "",
    val episodeId: String? = null,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val mediaType: String? = null,
    val audioTracks: List<ApiAudioTrack>? = null,
    val chapters: List<ApiChapter>? = null,
)

@Serializable
data class ApiAudioTrack(
    val index: Int = 0,
    val codec: String? = null,
    val title: String? = null,
    val duration: Double = 0.0,
    val contentUrl: String = "",
)

// ─── Progress ────────────────────────────────────────────────────────────

@Serializable
data class UpdateProgressRequest(
    val currentTime: Double,
    val isFinished: Boolean = false,
    val progress: Double = 0.0,
)

@Serializable
data class SyncSessionRequest(
    val currentTime: Double,
    val duration: Double,
    val timeListened: Double = 0.0,
)

@Serializable
data class ApiUserProgress(
    val libraryItemId: String = "",
    val currentTime: Double = 0.0,
    val progress: Double = 0.0,
    val isFinished: Boolean = false,
    val lastUpdate: Long = 0,
)

// ─── /api/me ─────────────────────────────────────────────────────────────

@Serializable
data class ApiMeResponse(
    val mediaProgress: List<ApiMeMediaProgress>? = null,
    val bookmarks: List<ApiBookmark>? = null,
)

@Serializable
data class ApiMeMediaProgress(
    val id: String = "",
    val libraryItemId: String = "",
    val episodeId: String? = null,
    val duration: Double = 0.0,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    val hideFromContinueListening: Boolean = false,
    val lastUpdate: Long = 0,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
)

@Serializable
data class ApiBookmark(
    val id: String = "",
    val libraryItemId: String = "",
    val title: String = "",
    val time: Double = 0.0,
    val createdAt: Long = 0,
)

// ─── Bookmark Request ────────────────────────────────────────────────────

@Serializable
data class CreateBookmarkRequest(
    val title: String,
    val time: Double,
)

// ─── Listening Sessions ─────────────────────────────────────────────────

@Serializable
data class ListeningSessionsResponse(
    val sessions: List<ApiListeningSession> = emptyList(),
    val total: Int = 0,
    val numPages: Int = 0,
    val page: Int = 0,
    val itemsPerPage: Int = 0,
)

@Serializable
data class ApiListeningSession(
    val id: String = "",
    val libraryItemId: String = "",
    val currentTime: Double = 0.0,
    val timeListening: Double = 0.0,
    val startTime: Double = 0.0,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    val displayTitle: String? = null,
    val dayOfWeek: String? = null,
)
