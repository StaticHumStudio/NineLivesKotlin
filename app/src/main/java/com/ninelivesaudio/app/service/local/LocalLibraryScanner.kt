package com.ninelivesaudio.app.service.local

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
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
    )

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
            )
        }

        val engine = LocalScanEngine(DocumentFileMetadataSource(metadataExtractor))
        val result = engine.scan(DocumentFileScanNode(rootDoc), rootTreeUri.toString())

        for (message in result.errorMessages) {
            Log.w(TAG, message)
        }
        Log.d(
            TAG,
            "Scan complete: ${result.books.size} books found, " +
                "${result.skippedCount} skipped, ${result.foldersScanned} folders scanned",
        )

        return ScanResult(
            books = result.books,
            skippedCount = result.skippedCount,
            errorMessages = result.errorMessages,
            foldersScanned = result.foldersScanned,
        )
    }
}

/** Wraps a [DocumentFile] so [LocalScanEngine] can walk it without any Android imports. */
private class DocumentFileScanNode(private val doc: DocumentFile) : ScanNode {
    override val name: String? get() = doc.name
    override val isDirectory: Boolean get() = doc.isDirectory
    override val uriString: String get() = doc.uri.toString()
    override val sizeBytes: Long get() = if (doc.isFile) doc.length() else 0L
    override val mimeType: String? get() = doc.type
    override fun children(): List<ScanNode> = doc.listFiles().map { DocumentFileScanNode(it) }
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
