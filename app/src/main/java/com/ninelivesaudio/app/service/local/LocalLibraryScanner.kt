package com.ninelivesaudio.app.service.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalLibraryScanner"

/**
 * Scans a SAF tree URI for audiobook folders and produces [ScannedLocalBook] entries.
 *
 * This class is a thin Android adapter: it wraps [DocumentFile] as a [ScanNode] and
 * [LocalMetadataExtractor] as a [ScanMetadataSource], then hands the actual traversal,
 * grouping, sorting, and id/title/author logic to [LocalScanEngine], which is plain
 * JVM code and carries the full rule set (see issue #17):
 *
 * - Any folder holding audio files directly becomes one book, at any depth.
 * - A folder whose subfolders all look like discs (CD1, Disc 2...) merges into one book.
 * - Loose audio files sitting in the picked folder each become their own single-file book.
 * - Hidden entries are skipped, and very deep or very large trees are cut off and reported.
 */
@Singleton
class LocalLibraryScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadataExtractor: LocalMetadataExtractor,
) {
    data class ScanResult(
        val books: List<ScannedLocalBook>,
        val skippedCount: Int,
        val errorMessages: List<String>,
        val foldersScanned: Int,
        val archiveFileCount: Int = 0,
        /**
         * True only when the scan walked the whole tree. False after an
         * unreadable folder or a cap-truncated traversal, and nothing may be
         * removed for being absent from a scan that says false. See
         * [LocalScanEngine.EngineResult].
         */
        val coverageComplete: Boolean = true,
        /** Books seen on disk but not built. Count as scanned, never removed. */
        val retainedBookIds: Set<String> = emptySet(),
    ) {
        /**
         * Every book id this scan can vouch for existing: the ones it built,
         * plus the ones it saw but could not build. This is the set a
         * reconciliation compares the database against.
         */
        fun seenBookIds(): List<String> = books.map { it.id } + retainedBookIds
    }

    companion object {
        internal val NATURAL_FILENAME_COMPARATOR: Comparator<String> =
            LocalScanEngine.NATURAL_FILENAME_COMPARATOR
    }

    /**
     * Scan a SAF tree URI and return discovered audiobooks.
     * This should be called on a background dispatcher.
     */
    fun scan(rootTreeUri: Uri): ScanResult {
        val rootDoc = DocumentFile.fromTreeUri(context, rootTreeUri)
        if (rootDoc == null || !rootDoc.canRead()) {
            Log.e(TAG, "Cannot read root tree URI: $rootTreeUri")
            return ScanResult(
                books = emptyList(),
                skippedCount = 0,
                errorMessages = listOf("Cannot read folder. Permission may have been revoked."),
                foldersScanned = 0,
                // An inaccessible root is the loudest possible failed listing.
                // Zero books here means "could not look", not "nothing there".
                coverageComplete = false,
            )
        }

        val engine = LocalScanEngine(DocumentFileMetadataSource(metadataExtractor))
        val rootNode = SafScanNode(
            resolver = context.contentResolver,
            treeUri = rootTreeUri,
            documentId = DocumentsContract.getTreeDocumentId(rootTreeUri),
            displayName = rootDoc.name,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            sizeBytes = 0L,
        )
        val result = try {
            engine.scan(rootNode, rootTreeUri.toString())
        } catch (e: Exception) {
            // The root's own listing failed. Same fact as an unreadable root:
            // nothing was seen, so nothing may be concluded about what is gone.
            Log.e(TAG, "Root listing failed for $rootTreeUri", e)
            return ScanResult(
                books = emptyList(),
                skippedCount = 0,
                errorMessages = listOf("Could not read folder contents: ${e.message}"),
                foldersScanned = 0,
                coverageComplete = false,
            )
        }

        for (message in result.errorMessages) {
            Log.w(TAG, message)
        }
        Log.d(
            TAG,
            "Scan complete: ${result.books.size} books found, " +
                "${result.skippedCount} skipped, ${result.foldersScanned} folders scanned, " +
                "${result.archiveFileCount} archives found",
        )

        return ScanResult(
            books = result.books,
            skippedCount = result.skippedCount,
            errorMessages = result.errorMessages,
            foldersScanned = result.foldersScanned,
            archiveFileCount = result.archiveFileCount,
            coverageComplete = result.coverageComplete,
            retainedBookIds = result.retainedBookIds,
        )
    }
}

/**
 * A SAF document as a [ScanNode], listed straight off the content resolver.
 *
 * Deliberately NOT built on [DocumentFile.listFiles]: that swallows a provider
 * or query failure and hands back the rows it managed to read (an empty array
 * in the worst case), which is indistinguishable from a folder that really is
 * empty. The scanner's whole removal guard rests on telling those two apart
 * (issue #20), so this queries the child-documents cursor itself and lets the
 * failure through as an exception. A null cursor counts as a failure too: a
 * healthy provider answers an empty folder with an empty cursor.
 *
 * The URIs it produces are byte-identical to the ones [DocumentFile] produced
 * (both are DocumentsContract.buildDocumentUriUsingTree over the same tree and
 * document ids), so existing local book and track ids are unchanged.
 */
private class SafScanNode(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val documentId: String,
    private val displayName: String?,
    override val mimeType: String?,
    override val sizeBytes: Long,
) : ScanNode {
    override val name: String? get() = displayName
    override val isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    override val uriString: String
        get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString()

    override fun children(): List<ScanNode> {
        if (!isDirectory) return emptyList()
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = resolver.query(childrenUri, PROJECTION, null, null, null)
            ?: throw IOException("No cursor for $childrenUri")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        SafScanNode(
                            resolver = resolver,
                            treeUri = treeUri,
                            documentId = it.getString(0),
                            displayName = if (it.isNull(1)) null else it.getString(1),
                            mimeType = if (it.isNull(2)) null else it.getString(2),
                            sizeBytes = if (it.isNull(3)) 0L else it.getLong(3),
                        )
                    )
                }
            }
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}

/** Bridges [LocalMetadataExtractor] (which speaks [Uri]) to the engine (which speaks strings). */
private class DocumentFileMetadataSource(
    private val extractor: LocalMetadataExtractor,
) : ScanMetadataSource {
    override fun extract(uriString: String): LocalMetadataExtractor.TrackMetadata? =
        extractor.extract(Uri.parse(uriString))

    override fun persistFolderCover(coverUriString: String?, bookId: String): String? =
        extractor.persistFolderCover(coverUriString, bookId)

    override fun extractEmbeddedCover(uriString: String, bookId: String): String? =
        extractor.extractEmbeddedCover(Uri.parse(uriString), bookId)
}
