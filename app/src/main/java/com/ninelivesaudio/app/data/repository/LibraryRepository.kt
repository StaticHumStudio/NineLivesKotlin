package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.LibraryDao
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.domain.model.Library
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val audioBookDao: AudioBookDao,
    private val apiService: ApiService,
) {
    /** Observe all libraries from local DB (reactive). */
    fun observeAll(): Flow<List<Library>> =
        libraryDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /** Observe Audiobookshelf libraries from local DB (reactive). */
    fun observeAudiobookshelf(): Flow<List<Library>> =
        libraryDao.observeAudiobookshelf().map { entities -> entities.map { it.toDomain() } }

    /** Observe Local Library roots from local DB (reactive). */
    fun observeLocalLibraries(): Flow<List<Library>> =
        libraryDao.observeLocal().map { entities -> entities.map { it.toDomain() } }

    /** Get all libraries from local DB (one-shot). */
    suspend fun getAll(): List<Library> =
        libraryDao.getAll().map { it.toDomain() }

    /** Get Audiobookshelf libraries from local DB (one-shot). */
    suspend fun getAudiobookshelf(): List<Library> =
        libraryDao.getAudiobookshelf().map { it.toDomain() }

    /** Get Local Library roots from local DB (one-shot). */
    suspend fun getLocalLibraries(): List<Library> =
        libraryDao.getLocal().map { it.toDomain() }

    /** Get a single library by ID. */
    suspend fun getById(id: String): Library? =
        libraryDao.getById(id)?.toDomain()

    /** Create or return a stable Local Library row for a persisted SAF folder URI. */
    suspend fun createLocalLibrary(name: String, folderUri: String): Library {
        val existing = libraryDao.getLocalByFolderUri(folderUri)
        if (existing != null) return existing.toDomain()

        val library = Library(
            id = stableLocalLibraryId(folderUri),
            name = name.ifBlank { "Local Library" },
            isLocal = true,
            folderUri = folderUri,
            mediaType = "book",
        )
        libraryDao.upsert(library.toEntity())
        return library
    }

    /** Fetch libraries from server and save to local DB. */
    suspend fun syncFromServer(): List<Library> {
        val remote = apiService.getLibraries()
        if (remote.isNotEmpty()) {
            libraryDao.upsertAll(remote.map { it.toEntity() })
        }
        return remote
    }

    /** Save a single library to local DB. */
    suspend fun save(library: Library) {
        libraryDao.upsert(library.toEntity())
    }

    /** Save multiple libraries to local DB. */
    suspend fun saveAll(libraries: List<Library>) {
        libraryDao.upsertAll(libraries.map { it.toEntity() })
    }

    /** Delete all libraries from local DB. */
    suspend fun deleteAll() {
        libraryDao.deleteAll()
    }

    /** Delete a Local Library row by ID without affecting ABS libraries. */
    suspend fun removeLocalLibrary(id: String) {
        audioBookDao.deleteLocalByLibrary(id)
        libraryDao.deleteLocalById(id)
    }

    private fun stableLocalLibraryId(folderUri: String): String =
        "local_library_${sha256(folderUri)}"

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
