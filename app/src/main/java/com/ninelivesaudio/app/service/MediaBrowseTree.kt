package com.ninelivesaudio.app.service

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.domain.model.isInActiveLibrary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a browse tree for Android Auto / MediaLibraryService.
 *
 * Media ID scheme:
 *   "root"                → browse root (shown when the user opens the app in Auto)
 *   "recently_played"     → recently played books
 *   "libraries"           → list of all libraries
 *   "lib_{id}"            → books in a specific library
 *   "book_{id}"           → a single playable book
 */
@Singleton
class MediaBrowseTree @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val audioBookRepository: AudioBookRepository,
    private val settingsManager: SettingsManager,
) {
    companion object {
        const val ROOT_ID = "root"
        const val RECENTLY_PLAYED_ID = "recently_played"
        const val LIBRARIES_ID = "libraries"
        private const val LIB_PREFIX = "lib_"
        private const val BOOK_PREFIX = "book_"
    }

    // ─── Public API ────────────────────────────────────────────────────

    /** Top-level items shown when the user opens the app in Android Auto. */
    @OptIn(UnstableApi::class)
    fun getRootItems(): List<MediaItem> = listOf(
        buildBrowsableItem(
            mediaId = RECENTLY_PLAYED_ID,
            title = "Recently Played",
            subtitle = "Continue listening",
        ),
        buildBrowsableItem(
            mediaId = LIBRARIES_ID,
            title = "Libraries",
            subtitle = "Browse your collection",
        ),
    )

    /** Resolve children for a given [parentId]. */
    suspend fun getChildren(parentId: String, page: Int = 0, pageSize: Int = 50): List<MediaItem> {
        val settings = resolveActiveScope()
        return when {
            parentId == ROOT_ID -> getRootItems()

            parentId == RECENTLY_PLAYED_ID -> {
                recentBooksInActiveScope(settings, limit = 20) { libraryId, limit ->
                    audioBookRepository.getRecentlyPlayedByLibrary(libraryId, limit)
                        .map { (book, _) -> book }
                }
                    .map(::bookToMediaItem)
            }

            parentId == LIBRARIES_ID -> {
                browseLibrariesInActiveScope(libraryRepository.getAll(), settings)
                    .map(::libraryToMediaItem)
            }

            parentId.startsWith(LIB_PREFIX) -> {
                val libId = parentId.removePrefix(LIB_PREFIX)
                browseBooksInActiveScope(audioBookRepository.getByLibrary(libId), settings)
                    .sortedBy { it.title.lowercase() }
                    .drop(page * pageSize)
                    .take(pageSize)
                    .map { bookToMediaItem(it) }
            }

            else -> emptyList()
        }
    }

    /** Get a single item by its media ID. */
    suspend fun getItem(mediaId: String): MediaItem? {
        val settings = resolveActiveScope()
        return when {
            mediaId == ROOT_ID -> buildBrowsableItem(ROOT_ID, "Nine Lives Audio", null)
            mediaId == RECENTLY_PLAYED_ID -> buildBrowsableItem(RECENTLY_PLAYED_ID, "Recently Played", "Continue listening")
            mediaId == LIBRARIES_ID -> buildBrowsableItem(LIBRARIES_ID, "Libraries", "Browse your collection")

            mediaId.startsWith(LIB_PREFIX) -> {
                val libId = mediaId.removePrefix(LIB_PREFIX)
                libraryRepository.getById(libId)
                    ?.takeIf { browseLibrariesInActiveScope(listOf(it), settings).isNotEmpty() }
                    ?.let(::libraryToMediaItem)
            }

            mediaId.startsWith(BOOK_PREFIX) -> {
                val bookId = mediaId.removePrefix(BOOK_PREFIX)
                // An archived book has no source, so don't resolve it as a
                // playable Auto item (e.g. from a stale queued media id).
                audioBookRepository.getById(bookId)
                    ?.takeIf { browseBooksInActiveScope(listOf(it), settings).isNotEmpty() }
                    ?.let(::bookToMediaItem)
            }

            else -> null
        }
    }

    /** Search books by title/author. */
    suspend fun search(query: String): List<MediaItem> {
        val settings = resolveActiveScope()
        return browseBooksInActiveScope(audioBookRepository.search(query), settings)
            .map(::bookToMediaItem)
    }

    /** Extract the original AudioBook ID from a media ID like "book_{id}". */
    fun extractBookId(mediaId: String): String? {
        return if (mediaId.startsWith(BOOK_PREFIX)) mediaId.removePrefix(BOOK_PREFIX) else null
    }

    private suspend fun resolveActiveScope(): AppSettings {
        val libraries = libraryRepository.getAll()
        val resolution = resolveActiveLibrarySelection(libraries, settingsManager.currentSettings)
        if (resolution.requiresPersistence) {
            settingsManager.updateSettings { latest ->
                resolveActiveLibrarySelection(libraries, latest).settings
            }
        }
        return settingsManager.currentSettings
    }

    // ─── Builders ──────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun bookToMediaItem(book: AudioBook): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .setAlbumTitle(book.title)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)

        if (!book.narrator.isNullOrEmpty()) {
            metadataBuilder.setComposer(book.narrator)
        }

        if (book.genres.isNotEmpty()) {
            metadataBuilder.setGenre(book.genres.first())
        }

        // Use the REMOTE cover URL here, not effectiveCoverPath. These browse
        // items go to out-of-process MediaBrowser clients (Android Auto), which
        // cannot read a downloaded book's app-private file:// cover under scoped
        // storage. A remote https URL is loadable by those clients when online;
        // the local file:// only helps in-process surfaces (the app UI and the
        // now-playing metadata, which embeds the bytes). Giving Auto offline
        // browse artwork needs a content:// URI + per-browser grant — tracked as
        // separate, Auto-tested work.
        if (!book.coverPath.isNullOrEmpty()) {
            metadataBuilder.setArtworkUri(Uri.parse(book.coverPath))
        }

        return MediaItem.Builder()
            .setMediaId("$BOOK_PREFIX${book.id}")
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun libraryToMediaItem(library: Library): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(library.name)
            .setIsPlayable(false)
            .setIsBrowsable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
            .build()

        return MediaItem.Builder()
            .setMediaId("$LIB_PREFIX${library.id}")
            .setMediaMetadata(metadata)
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun buildBrowsableItem(
        mediaId: String,
        title: String,
        subtitle: String?,
    ): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setIsPlayable(false)
            .setIsBrowsable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

        if (subtitle != null) {
            metadataBuilder.setSubtitle(subtitle)
        }

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }
}

internal fun browseLibrariesInActiveScope(
    libraries: List<Library>,
    settings: AppSettings,
): List<Library> = libraries.filter { library ->
    library.id == settings.activeLibraryId &&
        library.isLocal == (settings.appMode == com.ninelivesaudio.app.domain.model.AppMode.LOCAL)
}

internal fun browseBooksInActiveScope(
    books: List<AudioBook>,
    settings: AppSettings,
): List<AudioBook> = books.filter { book ->
    !book.isArchived && book.isInActiveLibrary(settings)
}

internal suspend fun recentBooksInActiveScope(
    settings: AppSettings,
    limit: Int,
    loadByLibrary: suspend (libraryId: String, limit: Int) -> List<AudioBook>,
): List<AudioBook> {
    val activeLibraryId = settings.activeLibraryId ?: return emptyList()
    return browseBooksInActiveScope(loadByLibrary(activeLibraryId, limit), settings)
}
