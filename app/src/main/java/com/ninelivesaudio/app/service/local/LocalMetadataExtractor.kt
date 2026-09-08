package com.ninelivesaudio.app.service.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "LocalMetadataExtractor"

/**
 * Extracts metadata (title, author, duration, artwork) from audio files
 * accessed through SAF content:// URIs using [MediaMetadataRetriever].
 */
@Singleton
class LocalMetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class TrackMetadata(
        val title: String? = null,
        val album: String? = null,
        val artist: String? = null,
        val albumArtist: String? = null,
        val duration: Duration = Duration.ZERO,
        val trackNumber: Int? = null,
        val mimeType: String? = null,
    )

    /**
     * Extract metadata from a single audio file URI.
     * Returns null if the file cannot be read or is not a valid audio file.
     */
    fun extract(fileUri: Uri): TrackMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, fileUri)

            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            val trackNum = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER
            )?.let { raw ->
                // Track numbers can be "3" or "3/12"
                raw.split("/").firstOrNull()?.trim()?.toIntOrNull()
            }

            TrackMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                duration = durationMs.milliseconds,
                trackNumber = trackNum,
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata from $fileUri: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Extract embedded artwork from an audio file and save it as a cover file
     * in app-private storage. Returns a file:// URI string (so Coil and other
     * image loaders recognize it without scheme guessing), or null if no
     * embedded artwork exists.
     */
    fun extractEmbeddedCover(fileUri: Uri, bookId: String): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, fileUri)
            val artBytes = retriever.embeddedPicture ?: return null

            val coverDir = File(context.filesDir, "local_covers")
            Uri.fromFile(writeLocalCoverFile(artBytes, coverDir, bookId)).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract embedded cover from $fileUri: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Copy a folder cover image (a content:// SAF URI that dies when the folder
     * is unscanned) into app-private storage and return a durable file:// URI.
     * Returns null if [coverUri] is null/blank, already a file://, or unreadable.
     */
    fun persistFolderCover(coverUri: String?, bookId: String): String? {
        if (coverUri.isNullOrBlank()) return null
        val parsed = Uri.parse(coverUri)
        if (parsed.scheme == "file") return coverUri // already durable (embedded)
        return try {
            val input = context.contentResolver.openInputStream(parsed) ?: return null
            val coverDir = File(context.filesDir, "local_covers")
            val coverFile = streamToLocalCoverFile(input, coverDir, bookId) ?: return null
            Uri.fromFile(coverFile).toString()
        } catch (e: Exception) {
            Log.w(TAG, "persistFolderCover failed for $bookId: ${e.message}")
            null
        }
    }
}

/**
 * Write cover bytes to <coverDir>/<bookId>.jpg (creating the dir) and return the
 * file. Pure file IO, unit-testable without the Android framework.
 */
internal fun writeLocalCoverFile(bytes: ByteArray, coverDir: File, bookId: String): File {
    coverDir.mkdirs()
    val file = File(coverDir, "$bookId.jpg")
    file.writeBytes(bytes)
    return file
}

/**
 * Copy folder-cover bytes to a sibling temporary file, then replace the durable
 * target only after a nonempty copy completes. This helper closes [input].
 */
internal fun streamToLocalCoverFile(
    input: InputStream,
    coverDir: File,
    bookId: String,
    openOutputStream: (File) -> OutputStream = { it.outputStream() },
): File? {
    return input.use { source ->
        if (!coverDir.exists() && !coverDir.mkdirs()) return@use null

        val target = File(coverDir, "$bookId.jpg")
        var tempFile: File? = null
        var committed = false
        try {
            val temp = File.createTempFile("cover-", ".tmp", coverDir)
            tempFile = temp
            openOutputStream(temp).use { output ->
                source.copyTo(output)
            }
            when {
                temp.length() == 0L -> null
                !temp.renameTo(target) -> null
                else -> {
                    committed = true
                    target
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            if (!committed) tempFile?.delete()
        }
    }
}
