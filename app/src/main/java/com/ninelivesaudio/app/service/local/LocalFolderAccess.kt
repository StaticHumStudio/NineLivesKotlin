package com.ninelivesaudio.app.service.local

import android.content.Context
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Library
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class LocalBookAccess(
    val book: AudioBook,
    val isPlayable: Boolean,
    val needsFolderRecovery: Boolean,
)

fun accessibleLocalLibraryIds(
    libraries: List<Library>,
    persistedReadGrantUris: Set<String>,
): Set<String> = libraries
    .asSequence()
    .filter { it.isLocal }
    .filter { !it.folderUri.isNullOrBlank() && it.folderUri in persistedReadGrantUris }
    .map { it.id }
    .toSet()

fun reconcileLocalBookAccess(
    book: AudioBook,
    accessibleLocalLibraryIds: Set<String>,
): LocalBookAccess {
    if (!book.isLocal) {
        return LocalBookAccess(
            book = book,
            isPlayable = !book.isArchived,
            needsFolderRecovery = false,
        )
    }

    val hasFolderAccess = book.libraryId in accessibleLocalLibraryIds
    return LocalBookAccess(
        book = if (hasFolderAccess) book else book.copy(isDownloaded = false),
        isPlayable = hasFolderAccess && !book.isArchived,
        needsFolderRecovery = !hasFolderAccess && !book.isArchived,
    )
}

@Singleton
class LocalFolderAccess @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun persistedReadGrantUris(): Set<String> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()

    fun accessibleLibraryIds(libraries: List<Library>): Set<String> =
        accessibleLocalLibraryIds(libraries, persistedReadGrantUris())
}
