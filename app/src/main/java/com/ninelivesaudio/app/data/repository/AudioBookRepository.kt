package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class AudioBookRepository @Inject constructor(
    private val audioBookDao: AudioBookDao,
    private val apiService: ApiService,
) {
    /** Observe all audiobooks (reactive). */
    fun observeAll(): Flow<List<AudioBook>> =
        audioBookDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /** Observe audiobooks for a specific library. */
    fun observeByLibrary(libraryId: String): Flow<List<AudioBook>> =
        audioBookDao.observeByLibrary(libraryId).map { entities -> entities.map { it.toDomain() } }

    /** Observe a single audiobook. */
    fun observeById(id: String): Flow<AudioBook?> =
        audioBookDao.observeById(id).map { it?.toDomain() }

    /** Get all audiobooks from local DB (one-shot). */
    suspend fun getAll(): List<AudioBook> =
        audioBookDao.getAll().map { it.toDomain() }

    /** Get audiobooks by library (one-shot). */
    suspend fun getByLibrary(libraryId: String): List<AudioBook> =
        audioBookDao.getByLibrary(libraryId).map { it.toDomain() }

    /** Get a single audiobook by ID. */
    suspend fun getById(id: String): AudioBook? =
        audioBookDao.getById(id)?.toDomain()

    /** Search audiobooks by title or author. */
    suspend fun search(query: String): List<AudioBook> =
        audioBookDao.search(query).map { it.toDomain() }

    /** Get recently played audiobooks for Nine Lives home screen. */
    suspend fun getRecentlyPlayed(limit: Int = 9): List<Pair<AudioBook, Long>> =
        audioBookDao.getRecentlyPlayed(limit).map { result ->
            val book = result.audioBook.toDomain()
            val lastPlayed = try {
                result.LastPlayedAt?.let { Instant.parse(it).toEpochMilli() } ?: 0L
            } catch (_: Exception) { 0L }
            book to lastPlayed
        }

    /** Observe recently played audiobooks (reactive). */
    fun observeRecentlyPlayed(limit: Int = 9): Flow<List<Pair<AudioBook, Long>>> =
        audioBookDao.observeRecentlyPlayed(limit).map { results ->
            results.map { result ->
                val book = result.audioBook.toDomain()
                val lastPlayed = try {
                    result.LastPlayedAt?.let { Instant.parse(it).toEpochMilli() } ?: 0L
                } catch (_: Exception) { 0L }
                book to lastPlayed
            }
        }

    /** Fetch all items for a library from server and save to local DB. */
    suspend fun syncLibraryItems(libraryId: String): List<AudioBook> {
        val remote = apiService.getLibraryItems(libraryId)
        if (remote.isNotEmpty()) {
            // Preserve local download info when syncing
            val localBooks = audioBookDao.getByLibrary(libraryId).associateBy { it.id }
            val merged = remote.map { remoteBook ->
                val local = localBooks[remoteBook.id]
                if (local != null) {
                    // Preserve local download state
                    val withDownload = if (local.isDownloaded == 1) {
                        remoteBook.copy(isDownloaded = true, localPath = local.localPath)
                    } else remoteBook

                    // Preserve local progress if it's ahead of server (offline playback)
                    val localTime = local.currentTimeSeconds
                    val remoteTime = withDownload.currentTime.inWholeMilliseconds / 1000.0
                    if (localTime > remoteTime) {
                        withDownload.copy(
                            currentTime = localTime.seconds,
                            progress = local.progress,
                            isFinished = local.isFinished == 1,
                        )
                    } else withDownload
                } else {
                    remoteBook
                }
            }
            audioBookDao.upsertAll(merged.map { it.toEntity() })
        }
        return remote
    }

    /** Fetch expanded item details from server. */
    suspend fun fetchFromServer(itemId: String): AudioBook? {
        return apiService.getAudioBook(itemId)
    }

    /** Save a single audiobook to local DB. */
    suspend fun save(audioBook: AudioBook) {
        audioBookDao.upsert(audioBook.toEntity())
    }

    /** Save multiple audiobooks to local DB. */
    suspend fun saveAll(audioBooks: List<AudioBook>) {
        audioBookDao.upsertAll(audioBooks.map { it.toEntity() })
    }

    /** Delete all audiobooks from local DB. */
    suspend fun deleteAll() {
        audioBookDao.deleteAll()
    }
}
