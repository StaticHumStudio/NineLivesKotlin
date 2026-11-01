package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.util.toEpochMillis
import com.ninelivesaudio.app.service.local.LocalFolderAccess
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** The reads a widget makes, always for one library id that [widgetAccess] chose. */
interface WidgetBookSource {
    suspend fun folderAccessible(libraryId: String): Boolean
    suspend fun countBooks(libraryId: String): Int

    /** The library's live books, the Library's All tab. */
    suspend fun shelf(libraryId: String): List<AudioBook>

    /** Most recently played first, with `lastPlayedAt` set. */
    suspend fun recent(libraryId: String, limit: Int): List<AudioBook>
}

@Singleton
class RoomWidgetBookSource @Inject constructor(
    private val audioBookRepository: AudioBookRepository,
    private val audioBookDao: AudioBookDao,
    private val libraryRepository: LibraryRepository,
    private val localFolderAccess: LocalFolderAccess,
) : WidgetBookSource {
    override suspend fun folderAccessible(libraryId: String): Boolean =
        libraryRepository.getById(libraryId)
            ?.let { localFolderAccess.accessibleLibraryIds(listOf(it)).contains(libraryId) }
            ?: false

    override suspend fun countBooks(libraryId: String): Int = audioBookRepository.countByLibrary(libraryId)

    override suspend fun shelf(libraryId: String): List<AudioBook> = audioBookRepository.getFilteredBooks(libraryId)

    override suspend fun recent(libraryId: String, limit: Int): List<AudioBook> =
        audioBookDao.getRecentlyPlayedByLibrary(libraryId, limit).map { row ->
            row.audioBook.toDomain().copy(lastPlayedAt = row.lastPlayedAt?.toEpochMillis())
        }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WidgetModule {
    @Binds
    abstract fun bindWidgetBookSource(impl: RoomWidgetBookSource): WidgetBookSource
}
