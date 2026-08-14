package com.ninelivesaudio.app.service

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.isInActiveLibrary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a browse tree for Android Auto / MediaLibraryService.
 *
 * Media ID scheme:
 *   "root"                → browse root (shown when the user opens the app in Auto)
 *   "recently_played"     → recently played books
 *   "library"             → all books in the active library or folder
 *   "downloaded"          → downloaded books in the active library or folder
 *   "book_{id}"           → a single playable book
 */
@Singleton
class MediaBrowseTree @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val audioBookRepository: AudioBookRepository,
    private val settingsManager: SettingsManager,
    private val apiService: ApiService,
) {
    companion object {
        const val ROOT_ID = "root"
        const val RECENTLY_PLAYED_ID = "recently_played"
        const val LIBRARY_ID = "library"
        const val DOWNLOADED_ID = "downloaded"
        const val SETUP_REQUIRED_ID = "setup_required"
        private const val BOOK_PREFIX = "book_"

        internal fun rootItemIds(): List<String> =
            listOf(RECENTLY_PLAYED_ID, LIBRARY_ID, DOWNLOADED_ID)
    }

    // ─── Public API ────────────────────────────────────────────────────

    /** Top-level items shown when the user opens the app in Android Auto. */
    @OptIn(UnstableApi::class)
    fun getRootItems(canBrowse: Boolean = true): List<MediaItem> = autoRootItemIds(canBrowse).map { mediaId ->
        when (mediaId) {
            RECENTLY_PLAYED_ID -> buildBrowsableItem(mediaId, "Recently Played", "Continue listening")
            LIBRARY_ID -> buildBrowsableItem(mediaId, "Library", "Browse the active source")
            DOWNLOADED_ID -> buildBrowsableItem(mediaId, "Downloaded", "Available offline")
            SETUP_REQUIRED_ID -> buildBrowsableItem(
                mediaId,
                "Open Nine Lives on your phone",
                "Connect a server or choose Local Files",
            )
            else -> error("Unknown Android Auto root item: $mediaId")
        }
    }

    /** Resolve children for a given [parentId]. */
    suspend fun getChildren(parentId: String, page: Int = 0, pageSize: Int = 50): List<MediaItem> {
        apiService.awaitAuthReady()
        val settings = resolveActiveScope()
        val canBrowse = canBrowseAuto(settings, apiService.isAuthenticated)
        if (parentId == ROOT_ID) return getRootItems(canBrowse)
        if (!canBrowse) return emptyList()
        return when {
            parentId == RECENTLY_PLAYED_ID -> {
                recentBooksForAuto(
                    settings = settings,
                    maxItems = 20,
                    page = page,
                    pageSize = pageSize,
                    loadByLibrary = { libraryId, isLocal, limit ->
                        audioBookRepository.getRecentlyPlayedForAuto(libraryId, isLocal, limit)
                            .map { (book, _) -> book }
                    },
                )
                    .map(::bookToMediaItem)
            }

            parentId == DOWNLOADED_ID -> {
                val libraryId = settings.activeLibraryId ?: return emptyList()
                downloadedBooksForAuto(
                    audioBookRepository.getFilteredBooks(libraryId, downloadedOnly = true),
                    settings,
                )
                    .sortedBy { it.title.lowercase() }
                    .drop(page * pageSize)
                    .take(pageSize)
                    .map(::bookToMediaItem)
            }

            parentId == LIBRARY_ID -> {
                val libraryId = settings.activeLibraryId ?: return emptyList()
                browseBooksForAuto(
                    audioBookRepository.getFilteredBooks(libraryId),
                    settings,
                )
                    .sortedBy { it.title.lowercase() }
                    .drop(page * pageSize)
                    .take(pageSize)
                    .map(::bookToMediaItem)
            }

            else -> emptyList()
        }
    }

    /** Count children for Media3 invalidation without constructing browse items. */
    suspend fun getChildCount(parentId: String): Int {
        apiService.awaitAuthReady()
        val settings = resolveActiveScope()
        return autoBrowseChildCount(
            parentId = parentId,
            canBrowse = canBrowseAuto(settings, apiService.isAuthenticated),
            activeLibraryId = settings.activeLibraryId,
            activeIsLocal = settings.appMode == AppMode.LOCAL,
            countRecent = audioBookRepository::countRecentlyPlayedForAuto,
            countLibrary = audioBookRepository::countForAuto,
            countDownloaded = audioBookRepository::countDownloadedForAuto,
        )
    }

    /** Get a single item by its media ID. */
    suspend fun getItem(mediaId: String): MediaItem? {
        apiService.awaitAuthReady()
        val settings = resolveActiveScope()
        if (mediaId != ROOT_ID && mediaId != SETUP_REQUIRED_ID &&
            !canBrowseAuto(settings, apiService.isAuthenticated)
        ) return null
        return when {
            mediaId == ROOT_ID -> buildBrowsableItem(ROOT_ID, "Nine Lives Audio", null)
            mediaId == SETUP_REQUIRED_ID -> buildBrowsableItem(
                SETUP_REQUIRED_ID,
                "Open Nine Lives on your phone",
                "Connect a server or choose Local Files",
            )
            mediaId == RECENTLY_PLAYED_ID -> buildBrowsableItem(RECENTLY_PLAYED_ID, "Recently Played", "Continue listening")
            mediaId == LIBRARY_ID -> buildBrowsableItem(LIBRARY_ID, "Library", "Browse the active source")
            mediaId == DOWNLOADED_ID -> buildBrowsableItem(DOWNLOADED_ID, "Downloaded", "Available offline")

            mediaId.startsWith(BOOK_PREFIX) -> {
                val bookId = mediaId.removePrefix(BOOK_PREFIX)
                // An archived book has no source, so don't resolve it as a
                // playable Auto item (e.g. from a stale queued media id).
                audioBookRepository.getById(bookId)
                    ?.takeIf { browseBooksForAuto(listOf(it), settings).isNotEmpty() }
                    ?.let(::bookToMediaItem)
            }

            else -> null
        }
    }

    /** Search books by title/author. */
    suspend fun search(query: String): List<MediaItem> {
        apiService.awaitAuthReady()
        val settings = resolveActiveScope()
        if (!canBrowseAuto(settings, apiService.isAuthenticated)) return emptyList()
        return browseBooksForAuto(audioBookRepository.search(query), settings)
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

internal fun browseBooksForAuto(
    books: List<AudioBook>,
    settings: AppSettings,
): List<AudioBook> = books.filter { book ->
    !book.isArchived && book.isInActiveLibrary(settings)
}

internal fun downloadedBooksForAuto(
    books: List<AudioBook>,
    settings: AppSettings,
): List<AudioBook> = browseBooksForAuto(books, settings).filter { it.isDownloaded }

internal suspend fun recentBooksForAuto(
    settings: AppSettings,
    maxItems: Int,
    page: Int,
    pageSize: Int,
    loadByLibrary: suspend (libraryId: String, isLocal: Boolean, limit: Int) -> List<AudioBook>,
): List<AudioBook> {
    val activeLibraryId = settings.activeLibraryId ?: return emptyList()
    if (maxItems <= 0 || page < 0 || pageSize <= 0) return emptyList()
    val offset = page.toLong() * pageSize.toLong()
    if (offset >= maxItems) return emptyList()
    val loadLimit = minOf(maxItems.toLong(), offset + pageSize.toLong()).toInt()
    val isLocal = settings.appMode == AppMode.LOCAL
    return browseBooksForAuto(loadByLibrary(activeLibraryId, isLocal, loadLimit), settings)
        .take(maxItems)
        .drop(offset.toInt())
        .take(pageSize)
}

internal fun canBrowseAuto(settings: AppSettings, isAuthenticated: Boolean): Boolean =
    when (settings.appMode) {
        AppMode.AUDIOBOOKSHELF -> isAuthenticated && settings.selectedLibraryId != null
        AppMode.LOCAL -> settings.selectedLocalLibraryId != null
    }

internal fun autoBrowseParentsChangedAfterSync(): List<String> =
    listOf(
        MediaBrowseTree.RECENTLY_PLAYED_ID,
        MediaBrowseTree.LIBRARY_ID,
        MediaBrowseTree.DOWNLOADED_ID,
    )

internal fun autoBrowseParentsChangedAfterSourceChange(): List<String> =
    listOf(MediaBrowseTree.ROOT_ID) + autoBrowseParentsChangedAfterSync()

internal fun autoRootItemIds(canBrowse: Boolean): List<String> =
    if (canBrowse) MediaBrowseTree.rootItemIds() else listOf(MediaBrowseTree.SETUP_REQUIRED_ID)

internal suspend fun autoBrowseChildCount(
    parentId: String,
    canBrowse: Boolean,
    activeLibraryId: String?,
    activeIsLocal: Boolean,
    countRecent: suspend (String, Boolean) -> Int,
    countLibrary: suspend (String, Boolean) -> Int,
    countDownloaded: suspend (String, Boolean) -> Int,
): Int {
    if (parentId == MediaBrowseTree.ROOT_ID) return autoRootItemIds(canBrowse).size
    if (!canBrowse) return 0
    val libraryId = activeLibraryId ?: return 0
    return when (parentId) {
        MediaBrowseTree.RECENTLY_PLAYED_ID -> minOf(20, countRecent(libraryId, activeIsLocal))
        MediaBrowseTree.LIBRARY_ID -> countLibrary(libraryId, activeIsLocal)
        MediaBrowseTree.DOWNLOADED_ID -> countDownloaded(libraryId, activeIsLocal)
        else -> 0
    }
}
