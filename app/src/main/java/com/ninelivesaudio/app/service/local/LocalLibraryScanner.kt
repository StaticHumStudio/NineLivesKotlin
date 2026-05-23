package com.ninelivesaudio.app.service.local

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

private const val TAG = "LocalLibraryScanner"

/**
 * Scans a SAF tree URI for audiobook folders and produces [ScannedLocalBook] entries.
 *
 * Scanning rules:
 * - Each immediate child **directory** containing at least one audio file becomes one book.
 * - Each immediate audio file at the root (not inside a subdirectory) becomes a single-file book.
 * - Audio files are sorted by track number metadata when available, then by natural filename order.
 * - Cover images are detected by well-known filenames (cover.jpg, folder.jpg, etc.) or
 *   extracted from embedded artwork in the first audio file.
 * - Hidden files and non-audio/non-image files are skipped.
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
    )

    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "opus", "ogg", "flac", "aac", "wma", "wav",
        )

        private val COVER_FILENAMES = setOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.jpeg", "folder.png",
        )

        // Natural-order comparator: splits on digit runs so "2.mp3" < "10.mp3".
        private val DIGIT_RUN = Regex("\\d+|\\D+")
        internal val NATURAL_FILENAME_COMPARATOR: Comparator<String> = Comparator { a, b ->
            val ap = DIGIT_RUN.findAll(a.lowercase()).map { it.value }.toList()
            val bp = DIGIT_RUN.findAll(b.lowercase()).map { it.value }.toList()
            val n = minOf(ap.size, bp.size)
            var i = 0
            var result = 0
            while (i < n) {
                val x = ap[i]
                val y = bp[i]
                val cmp = if (x.first().isDigit() && y.first().isDigit()) {
                    // Compare as numbers; fall back to string compare on overflow.
                    val xn = x.toLongOrNull()
                    val yn = y.toLongOrNull()
                    if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
                } else {
                    x.compareTo(y)
                }
                if (cmp != 0) { result = cmp; break }
                i++
            }
            if (result != 0) result else ap.size.compareTo(bp.size)
        }
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
            )
        }

        val books = mutableListOf<ScannedLocalBook>()
        val errors = mutableListOf<String>()
        var skipped = 0

        val children = rootDoc.listFiles()

        // Separate immediate audio files from subdirectories
        val rootAudioFiles = mutableListOf<DocumentFile>()
        val bookFolders = mutableListOf<DocumentFile>()

        for (child in children) {
            if (isHidden(child)) continue

            if (child.isDirectory) {
                bookFolders.add(child)
            } else if (child.isFile && isAudioFile(child)) {
                rootAudioFiles.add(child)
            }
        }

        // Each subdirectory with audio files is one book
        for (folder in bookFolders) {
            try {
                val book = scanBookFolder(folder, rootTreeUri.toString())
                if (book != null) {
                    books.add(book)
                } else {
                    skipped++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error scanning folder ${folder.name}: ${e.message}")
                errors.add("Skipped folder '${folder.name}': ${e.message}")
                skipped++
            }
        }

        // Each root-level audio file is a single-file book
        for (audioFile in rootAudioFiles) {
            try {
                val book = scanSingleFileBook(audioFile, rootTreeUri.toString())
                if (book != null) {
                    books.add(book)
                } else {
                    skipped++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error scanning file ${audioFile.name}: ${e.message}")
                errors.add("Skipped file '${audioFile.name}': ${e.message}")
                skipped++
            }
        }

        Log.d(TAG, "Scan complete: ${books.size} books found, $skipped skipped")
        return ScanResult(books = books, skippedCount = skipped, errorMessages = errors)
    }

    /**
     * Scan a directory as a single audiobook.
     * Returns null if the directory contains no audio files.
     */
    private fun scanBookFolder(folder: DocumentFile, rootUri: String): ScannedLocalBook? {
        val allChildren = folder.listFiles()
        val audioFiles = allChildren
            .filter { it.isFile && isAudioFile(it) && !isHidden(it) }
            .toMutableList()

        if (audioFiles.isEmpty()) return null

        // Extract metadata from first audio file for book-level info
        val firstFileUri = audioFiles.first().uri
        val bookMeta = metadataExtractor.extract(firstFileUri)

        // Extract per-track metadata for sorting and duration
        data class TrackWithMeta(
            val file: DocumentFile,
            val meta: LocalMetadataExtractor.TrackMetadata?,
        )
        val tracksWithMeta = audioFiles.map { file ->
            TrackWithMeta(file, metadataExtractor.extract(file.uri))
        }

        // Sort: by track number if available, otherwise by natural filename order
        // (so "2.mp3" sorts before "10.mp3" instead of after).
        val sorted = tracksWithMeta.sortedWith(
            compareBy<TrackWithMeta> { it.meta?.trackNumber ?: Int.MAX_VALUE }
                .then(compareBy(NATURAL_FILENAME_COMPARATOR) { it.file.name ?: "" })
        )

        // Build track list
        val tracks = sorted.mapIndexed { index, twm ->
            val fileUri = twm.file.uri.toString()
            ScannedTrack(
                id = "local_file_${sha256(fileUri)}",
                uri = fileUri,
                filename = twm.file.name ?: "track_${index + 1}",
                index = index,
                duration = twm.meta?.duration ?: Duration.ZERO,
                mimeType = twm.meta?.mimeType ?: twm.file.type,
                size = twm.file.length(),
            )
        }

        val totalDuration = tracks.fold(Duration.ZERO) { acc, t -> acc + t.duration }
        val folderName = folder.name ?: "Unknown"

        // Resolve book title: album tag, then folder name
        val title = bookMeta?.album?.takeIf { it.isNotBlank() }
            ?: folderName

        // Resolve author: album artist, then artist, then Unknown Author
        val author = bookMeta?.albumArtist?.takeIf { it.isNotBlank() }
            ?: bookMeta?.artist?.takeIf { it.isNotBlank() }
            ?: "Unknown Author"

        // Resolve cover
        val bookId = "local_book_${sha256("$rootUri/$folderName")}"
        val coverUri = findCoverImage(allChildren) ?: metadataExtractor.extractEmbeddedCover(firstFileUri, bookId)

        return ScannedLocalBook(
            id = bookId,
            title = title,
            author = author,
            coverUri = coverUri,
            duration = totalDuration,
            tracks = tracks,
        )
    }

    /**
     * Scan a single root-level audio file as a one-track book.
     */
    private fun scanSingleFileBook(file: DocumentFile, rootUri: String): ScannedLocalBook? {
        val fileUri = file.uri
        val meta = metadataExtractor.extract(fileUri)

        val filename = file.name ?: return null
        val bookId = "local_book_${sha256("$rootUri/$filename")}"

        // Title: embedded title or album, then filename without extension
        val title = meta?.title?.takeIf { it.isNotBlank() }
            ?: meta?.album?.takeIf { it.isNotBlank() }
            ?: filename.substringBeforeLast(".")

        val author = meta?.albumArtist?.takeIf { it.isNotBlank() }
            ?: meta?.artist?.takeIf { it.isNotBlank() }
            ?: "Unknown Author"

        val coverUri = metadataExtractor.extractEmbeddedCover(fileUri, bookId)

        val track = ScannedTrack(
            id = "local_file_${sha256(fileUri.toString())}",
            uri = fileUri.toString(),
            filename = filename,
            index = 0,
            duration = meta?.duration ?: Duration.ZERO,
            mimeType = meta?.mimeType ?: file.type,
            size = file.length(),
        )

        return ScannedLocalBook(
            id = bookId,
            title = title,
            author = author,
            coverUri = coverUri,
            duration = meta?.duration ?: Duration.ZERO,
            tracks = listOf(track),
        )
    }

    /**
     * Look for a cover image file among the children of a book folder.
     * Returns the content:// URI string, or null.
     */
    private fun findCoverImage(children: Array<DocumentFile>): String? {
        for (child in children) {
            if (!child.isFile) continue
            val name = child.name?.lowercase() ?: continue
            if (name in COVER_FILENAMES) {
                return child.uri.toString()
            }
        }
        return null
    }

    private fun isAudioFile(file: DocumentFile): Boolean {
        val name = file.name?.lowercase() ?: return false
        val ext = name.substringAfterLast('.', "")
        return ext in AUDIO_EXTENSIONS
    }

    private fun isHidden(file: DocumentFile): Boolean {
        val name = file.name ?: return true
        return name.startsWith(".")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
