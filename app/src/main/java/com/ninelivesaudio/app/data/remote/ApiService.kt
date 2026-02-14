package com.ninelivesaudio.app.data.remote

import android.os.Build
import com.ninelivesaudio.app.data.remote.dto.*
import com.ninelivesaudio.app.domain.model.*
import com.ninelivesaudio.app.service.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * High-level API service that wraps Retrofit calls with error handling and
 * maps API DTOs to domain models. Ports the C# AudioBookshelfApiService logic.
 */
@Singleton
class ApiService @Inject constructor(
    private val api: AudiobookshelfApi,
    private val authInterceptor: AuthInterceptor,
    private val settingsManager: SettingsManager,
) {
    var lastError: String? = null
        private set

    val isAuthenticated: Boolean
        get() = authInterceptor.hasToken() && settingsManager.currentSettings.serverUrl.isNotEmpty()

    // ─── Auth ────────────────────────────────────────────────────────────

    suspend fun login(serverUrl: String, username: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeServerUrl(serverUrl)
                val normalizedUsername = username.trim()

                // Update settings with server URL first (so Retrofit uses it)
                settingsManager.updateSettings { it.copy(serverUrl = normalizedUrl, username = normalizedUsername) }

                val response = api.login(LoginRequest(normalizedUsername, password))

                if (!response.isSuccessful) {
                    lastError = "Login failed: ${response.code()} - ${response.errorBody()?.string()}"
                    return@withContext false
                }

                val loginResponse = response.body()
                val token = loginResponse?.user?.token

                if (token.isNullOrEmpty()) {
                    lastError = "Server response did not contain authentication token"
                    return@withContext false
                }

                // Save token and update interceptor
                authInterceptor.setToken(token)
                settingsManager.saveAuthToken(token)

                lastError = null
                true
            } catch (e: Exception) {
                lastError = "Connection failed: ${e.message}"
                false
            }
        }
    }

    suspend fun logout() {
        authInterceptor.setToken(null)
        settingsManager.clearAuthToken()
    }

    suspend fun validateToken(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Try to restore token from secure storage
                val token = settingsManager.getAuthToken()
                if (token.isNullOrEmpty()) return@withContext false

                authInterceptor.setToken(token)

                val response = api.getMe()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Restore token from secure storage on app startup. */
    suspend fun initializeFromSettings() {
        val token = settingsManager.getAuthToken()
        if (!token.isNullOrEmpty()) {
            authInterceptor.setToken(token)
        }
    }

    // ─── Libraries ───────────────────────────────────────────────────────

    suspend fun getLibraries(): List<Library> = withContext(Dispatchers.IO) {
        try {
            val response = api.getLibraries()
            if (!response.isSuccessful) return@withContext emptyList()

            response.body()?.libraries?.map { apiLib ->
                Library(
                    id = apiLib.id,
                    name = apiLib.name,
                    displayOrder = apiLib.displayOrder,
                    icon = apiLib.icon ?: "audiobook",
                    mediaType = apiLib.mediaType ?: "book",
                    folders = apiLib.folders?.map { f ->
                        Folder(id = f.id, fullPath = f.fullPath, libraryId = apiLib.id)
                    } ?: emptyList()
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── Library Items (Paginated batch load) ────────────────────────────

    suspend fun getLibraryItems(libraryId: String, limit: Int = 100): List<AudioBook> =
        withContext(Dispatchers.IO) {
            try {
                val allItems = mutableListOf<AudioBook>()
                var currentPage = 0

                while (true) {
                    val response = api.getLibraryItems(libraryId, limit, currentPage)
                    if (!response.isSuccessful) break

                    val body = response.body() ?: break
                    if (body.results.isEmpty()) break

                    allItems.addAll(body.results.map { mapToAudioBook(it, libraryId) })

                    if (allItems.size >= body.total) break
                    currentPage++
                }

                allItems
            } catch (e: Exception) {
                emptyList()
            }
        }

    // ─── Single Item ─────────────────────────────────────────────────────

    suspend fun getAudioBook(itemId: String): AudioBook? = withContext(Dispatchers.IO) {
        try {
            val response = api.getItem(itemId)
            if (!response.isSuccessful) return@withContext null
            response.body()?.let { mapToAudioBook(it) }
        } catch (e: Exception) {
            null
        }
    }

    // ─── Playback Session ────────────────────────────────────────────────

    suspend fun startPlaybackSession(itemId: String): PlaybackSessionInfo? =
        withContext(Dispatchers.IO) {
            try {
                val request = StartPlaybackRequest(
                    deviceInfo = DeviceInfo(
                        clientName = "NineLivesAudio",
                        deviceId = Build.MODEL,
                    )
                )
                val response = api.startPlaybackSession(itemId, request)
                if (!response.isSuccessful) return@withContext null

                val session = response.body() ?: return@withContext null
                val serverUrl = settingsManager.currentSettings.serverUrl
                val token = settingsManager.getAuthToken() ?: ""

                PlaybackSessionInfo(
                    id = session.id,
                    itemId = session.libraryItemId,
                    episodeId = session.episodeId,
                    currentTime = session.currentTime,
                    duration = session.duration,
                    mediaType = session.mediaType ?: "book",
                    audioTracks = session.audioTracks?.map { t ->
                        val contentUrl = if (t.contentUrl.startsWith("http")) {
                            "${t.contentUrl}?token=$token"
                        } else {
                            "$serverUrl${t.contentUrl}?token=$token"
                        }
                        AudioStreamInfo(
                            index = t.index,
                            codec = t.codec ?: "mp3",
                            title = t.title,
                            duration = t.duration,
                            contentUrl = contentUrl,
                        )
                    } ?: emptyList(),
                    chapters = session.chapters?.map { c ->
                        Chapter(id = c.id, start = c.start, end = c.end, title = c.title)
                    } ?: emptyList(),
                )
            } catch (e: Exception) {
                null
            }
        }

    suspend fun syncSessionProgress(
        sessionId: String,
        currentTime: Double,
        duration: Double,
        timeListened: Double = 0.0,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = api.syncSessionProgress(
                sessionId,
                SyncSessionRequest(currentTime, duration, timeListened)
            )
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun closeSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            try {
                api.closeSession(sessionId)
            } catch (_: Exception) {}
        }
    }

    // ─── Progress ────────────────────────────────────────────────────────

    suspend fun updateProgress(
        itemId: String,
        currentTime: Double,
        isFinished: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = api.updateProgress(
                itemId,
                // API expects progress as fraction [0, 1], not seconds.
                UpdateProgressRequest(
                    currentTime = currentTime.coerceAtLeast(0.0),
                    isFinished = isFinished,
                    progress = if (isFinished) 1.0 else 0.0,
                )
            )
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getUserProgress(itemId: String): UserProgress? = withContext(Dispatchers.IO) {
        try {
            val response = api.getUserProgress(itemId)
            if (!response.isSuccessful) return@withContext null
            response.body()?.let { p ->
                    UserProgress(
                        libraryItemId = p.libraryItemId,
                        currentTime = p.currentTime.seconds,
                        progress = normalizeProgress(p.progress),
                        isFinished = p.isFinished,
                        lastUpdate = if (p.lastUpdate > 0) p.lastUpdate else null,
                    )
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getAllUserProgress(): List<UserProgress> = withContext(Dispatchers.IO) {
        try {
            val response = api.getMe()
            if (!response.isSuccessful) return@withContext emptyList()

            response.body()?.mediaProgress
                ?.filter { it.libraryItemId.isNotEmpty() }
                ?.map { p ->
                    UserProgress(
                        libraryItemId = p.libraryItemId,
                        currentTime = p.currentTime.seconds,
                        progress = normalizeProgress(p.progress),
                        isFinished = p.isFinished,
                        lastUpdate = if (p.lastUpdate > 0) p.lastUpdate else null,
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── Bookmarks ───────────────────────────────────────────────────────

    suspend fun getBookmarks(itemId: String): List<Bookmark> = withContext(Dispatchers.IO) {
        try {
            val response = api.getMe()
            if (!response.isSuccessful) return@withContext emptyList()

            response.body()?.bookmarks
                ?.filter { it.libraryItemId == itemId }
                ?.sortedBy { it.time }
                ?.map { b ->
                    Bookmark(
                        id = b.id,
                        libraryItemId = b.libraryItemId,
                        title = b.title,
                        time = b.time,
                        createdAt = b.createdAt,
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun createBookmark(itemId: String, title: String, time: Double): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val response = api.createBookmark(itemId, CreateBookmarkRequest(title, time))
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }

    suspend fun deleteBookmark(itemId: String, time: Double): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val response = api.deleteBookmark(itemId, time)
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }

    // ─── Cover Image URL ─────────────────────────────────────────────────

    fun getCoverUrl(itemId: String): String {
        val serverUrl = settingsManager.currentSettings.serverUrl
        val encodedItemId = URLEncoder.encode(itemId, StandardCharsets.UTF_8.toString())
        return "$serverUrl/api/items/$encodedItemId/cover"
    }

    // ─── Mapping Helpers ─────────────────────────────────────────────────

    private fun mapToAudioBook(item: ApiLibraryItem, libraryId: String? = null): AudioBook {
        val metadata = item.media?.metadata
        val audioFiles = item.media?.audioFiles ?: emptyList()
        val firstSeries = metadata?.series?.firstOrNull()
        val serverUrl = settingsManager.currentSettings.serverUrl

        return AudioBook(
            id = item.id,
            libraryId = libraryId ?: item.libraryId,
            title = metadata?.title ?: "Unknown Title",
            author = metadata?.authorName
                ?: metadata?.authors?.firstOrNull()?.name
                ?: "Unknown Author",
            narrator = metadata?.narratorName ?: metadata?.narrators?.firstOrNull(),
            description = metadata?.description,
            coverPath = if (!item.media?.coverPath.isNullOrEmpty()) {
                "$serverUrl/api/items/${item.id}/cover"
            } else null,
            duration = (item.media?.duration ?: 0.0).seconds,
            addedAt = item.addedAt,
            seriesName = firstSeries?.name ?: metadata?.seriesName,
            seriesSequence = firstSeries?.sequence,
            genres = metadata?.genres ?: emptyList(),
            tags = metadata?.tags ?: emptyList(),
            audioFiles = audioFiles.mapIndexed { idx, af ->
                AudioFile(
                    id = af.ino ?: idx.toString(),
                    ino = af.ino ?: "",
                    index = af.index ?: idx,
                    duration = (af.duration ?: 0.0).seconds,
                    filename = af.metadata?.filename ?: "track_${idx + 1}",
                    mimeType = af.mimeType,
                    size = af.metadata?.size ?: 0,
                )
            },
            chapters = item.media?.chapters
                ?.filter { c -> c.start >= 0.0 && c.end > c.start }
                ?.map { c -> Chapter(id = c.id, start = c.start, end = c.end, title = c.title) }
                ?: emptyList(),
            currentTime = (item.userMediaProgress?.currentTime ?: 0.0).seconds,
            progress = normalizeProgress(item.userMediaProgress?.progress ?: 0.0),
            isFinished = item.userMediaProgress?.isFinished ?: false,
        )
    }

    private fun normalizeProgress(value: Double): Double {
        val nonNegative = value.coerceAtLeast(0.0)
        return if (nonNegative > 1.0) {
            (nonNegative / 100.0).coerceIn(0.0, 1.0)
        } else {
            nonNegative.coerceIn(0.0, 1.0)
        }
    }

    private fun normalizeServerUrl(url: String): String {
        var normalized = url.trim().replace("\\", "/")
        if ("://" !in normalized) {
            normalized = when {
                normalized.startsWith("https:", ignoreCase = true) ->
                    "https://" + normalized.substringAfter(":", "").trimStart('/')
                normalized.startsWith("http:", ignoreCase = true) ->
                    "http://" + normalized.substringAfter(":", "").trimStart('/')
                else -> "http://$normalized"
            }
        }
        return normalized.trimEnd('/')
    }
}
