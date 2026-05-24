package com.ninelivesaudio.app.service.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
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
            coverDir.mkdirs()
            val coverFile = File(coverDir, "$bookId.jpg")
            FileOutputStream(coverFile).use { it.write(artBytes) }

            Uri.fromFile(coverFile).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract embedded cover from $fileUri: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
