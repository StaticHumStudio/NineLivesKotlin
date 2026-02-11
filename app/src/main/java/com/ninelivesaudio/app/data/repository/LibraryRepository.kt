package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.LibraryDao
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.domain.model.Library
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val apiService: ApiService,
) {
    /** Observe all libraries from local DB (reactive). */
    fun observeAll(): Flow<List<Library>> =
        libraryDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /** Get all libraries from local DB (one-shot). */
    suspend fun getAll(): List<Library> =
        libraryDao.getAll().map { it.toDomain() }

    /** Get a single library by ID. */
    suspend fun getById(id: String): Library? =
        libraryDao.getById(id)?.toDomain()

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
}
