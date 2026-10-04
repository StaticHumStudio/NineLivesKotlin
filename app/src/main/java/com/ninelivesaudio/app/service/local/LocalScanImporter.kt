package com.ninelivesaudio.app.service.local

import android.net.Uri
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.ui.settings.failEmptyScanWithErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The work behind Add Folder and Rescan: scan the folder, import what it
 * found, and reconcile the library against it. [LocalScanCoordinator] runs
 * this in an app-wide scope so leaving Settings no longer throws a scan away
 * (issue #57). Settings only shows the result.
 */
@Singleton
class LocalScanImporter @Inject constructor(
    private val localScanner: LocalLibraryScanner,
    private val localMetadataExtractor: LocalMetadataExtractor,
    private val libraryRepository: LibraryRepository,
    private val audioBookRepository: AudioBookRepository,
    private val settingsManager: SettingsManager,
) {

    suspend fun run(request: LocalScanRequest): LocalScanImport = when (request) {
        is LocalScanRequest.AddFolder -> addFolder(request.folderUri)
        is LocalScanRequest.Rescan -> rescan(request.library)
    }

    private suspend fun addFolder(uriString: String): LocalScanImport {
        // Derive a display name from the URI path
        val uri = Uri.parse(uriString)
        val displayName = uri.lastPathSegment
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
            ?.ifBlank { null }
            ?: "Local Library"

        val scanResult = withContext(Dispatchers.IO) { localScanner.scan(uri) }
        failEmptyScanWithErrors(scanResult)

        // Create or reuse the local library row after confirming the folder is readable.
        val library = libraryRepository.createLocalLibrary(displayName, uriString)

        importAndReconcile(library.id, scanResult)

        // Select this library
        settingsManager.updateSettings {
            it.copy(selectedLocalLibraryId = library.id)
        }
        return LocalScanImport(library, scanResult)
    }

    private suspend fun rescan(library: Library): LocalScanImport {
        val folderUri = requireNotNull(library.folderUri) { "This library has no folder" }
        val scanResult = withContext(Dispatchers.IO) { localScanner.scan(Uri.parse(folderUri)) }
        failEmptyScanWithErrors(scanResult)

        importAndReconcile(library.id, scanResult)
        return LocalScanImport(library, scanResult)
    }

    /**
     * Import what the scan found, then reconcile the library against it: any
     * local book whose folder was not seen in this scan leaves the library
     * (issue #20). Without this a rescan only ever adds, so moving a folder one
     * level deeper leaves a ghost entry pointing at files that are gone, and
     * playing it fails.
     *
     * Removal is gated on [LocalLibraryScanner.ScanResult.coverageComplete], NOT
     * on "the scan reported no warnings". The distinction is the whole safety
     * argument: a folder that could not be listed, a revoked permission, or a
     * traversal cut short by the depth or folder cap all mean books exist that
     * this scan never saw, and absence from a partial inventory is not evidence
     * a book is gone. Those scans import and reconcile nothing. A warning that
     * is not a coverage gap (a single unreadable file, whose id the scan hands
     * back as retained) no longer freezes the whole library forever.
     *
     * "Leaves the library" means archived, not deleted. A folder can come back:
     * the card is out of the library and its Archive tab entry keeps the cover,
     * progress, and history, and re-adding the folder restores it in place. The
     * Orphaned Books sweep in Settings stays the one path that actually
     * deletes rows, and it already cascades progress, sessions, bookmarks, and
     * the local_covers file.
     */
    private suspend fun importAndReconcile(
        libraryId: String,
        scanResult: LocalLibraryScanner.ScanResult,
    ) {
        val books = scanResult.books.map { it.toAudioBook(libraryId) }
        val seenIds = scanResult.seenBookIds()
        // Captured before the import, so a book that arrived in THIS scan is
        // distinguishable from one that was already in the library.
        val existingIds = if (scanResult.coverageComplete) {
            audioBookRepository.getLiveLocalIds(libraryId)
        } else {
            emptyList()
        }

        audioBookRepository.importLocalBooksCarryingMoves(
            libraryId = libraryId,
            books = books,
            existingIds = existingIds,
            seenIds = seenIds,
        )

        if (!scanResult.coverageComplete) return

        archiveMissingBooks(libraryId, seenIds)
    }

    /**
     * The single archive choke point: before soft-deleting the books missing
     * from [scannedIds], copy any still-readable content:// folder cover to
     * durable storage. Both archive triggers (whole-folder Remove and the
     * missing-books pass after a rescan) route through here, so a book scanned
     * under an older build keeps its cover once archived — the folder is still
     * accessible at this point (permission not yet released; a rescan only
     * archives what a clean scan reported missing). Already-durable and
     * unreadable covers are left as-is (best-effort).
     */
    suspend fun archiveMissingBooks(libraryId: String, scannedIds: List<String>) {
        audioBookRepository.persistFolderCovers(libraryId) { uri, id ->
            localMetadataExtractor.persistFolderCover(uri, id)
        }
        audioBookRepository.removeMissingLocalBooks(libraryId, scannedIds)
    }
}
