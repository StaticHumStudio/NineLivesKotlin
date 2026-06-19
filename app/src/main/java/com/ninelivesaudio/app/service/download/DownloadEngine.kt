package com.ninelivesaudio.app.service.download

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.data.remote.AudiobookshelfApi
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.service.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Streams an audiobook's audio files to disk: `.part`-then-atomic-rename,
 * skip-already-finished, per-file retry with exponential backoff, throttled
 * progress persistence, and cancellation cleanup.
 *
 * This is the side-effecting core of downloading, pulled out of DownloadManager
 * so it can be driven either by the legacy in-process scope or by a WorkManager
 * worker. The error-prone decisions live in [DownloadPolicies] and are unit
 * tested; the streaming orchestration here is verified by the on-device pass.
 */
@Singleton
class DownloadEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadItemDao: DownloadItemDao,
    private val audioBookDao: AudioBookDao,
    private val api: AudiobookshelfApi,
    private val settingsManager: SettingsManager,
) {
    companion object {
        private const val TAG = "DownloadEngine"
        private const val BUFFER_SIZE = 81_920 // 80 KB
    }

    /**
     * Download every audio file of [audioBook] for [item]. Persists status and
     * progress to Room throughout and invokes [onProgress] for UI liveliness.
     * Returns the terminal [DownloadItem] (Completed / Failed / Paused); the
     * caller owns any completion/failure event emission.
     */
    suspend fun download(
        item: DownloadItem,
        audioBook: AudioBook,
        onProgress: suspend (downloadId: String, downloaded: Long, total: Long) -> Unit,
    ): DownloadItem {
        var download = item.copy(status = DownloadStatus.Downloading)
        downloadItemDao.upsert(download.toEntity())

        // Create download directory
        val downloadDir = getDownloadPath(audioBook)
        downloadDir.mkdirs()

        // Get full book details if audio files are missing
        val book = if (audioBook.audioFiles.isEmpty()) {
            fetchFullBookDetails(audioBook.id) ?: audioBook
        } else {
            audioBook
        }

        if (book.audioFiles.isEmpty()) {
            download = download.copy(
                status = DownloadStatus.Failed,
                errorMessage = "No audio files available for download",
            )
            downloadItemDao.upsert(download.toEntity())
            return download
        }

        val totalBytes = estimateTotalBytes(book.audioFiles)
        download = download.copy(totalBytes = totalBytes)

        var downloadedBytes = 0L
        var lastPersistedBytes = 0L
        var lastPersistedAt = System.currentTimeMillis()
        val maxRetries = item.maxRetries

        // Download each audio file
        for (i in book.audioFiles.indices) {
            val audioFile = book.audioFiles[i]

            // Check for cancellation
            currentCoroutineContext().ensureActive()

            val fileName = sanitizeDownloadFileName(audioFile.filename.ifEmpty { "track_${i + 1}" })
            val finalPath = File(downloadDir, fileName)
            val partPath = File(downloadDir, "$fileName.part")

            // Skip if already downloaded
            if (shouldSkipDownloadedFile(finalPath.exists(), finalPath.length())) {
                downloadedBytes += finalPath.length()
                onProgress(download.id, downloadedBytes, totalBytes)
                continue
            }

            // Retry loop for the CURRENT file. The old code used `continue`
            // in the catch block which advanced the for-loop index, skipping
            // the failed file instead of retrying it.
            var retryCount = 0
            var fileSuccess = false
            while (!fileSuccess) {
                // Snapshot byte count before this attempt — used to revert on retry
                val bytesBeforeAttempt = downloadedBytes

                try {
                    // Stream the file
                    if (audioFile.ino.isBlank()) {
                        throw Exception("Missing audio file identifier for $fileName")
                    }

                    val response = api.getAudioFileStream(book.id, audioFile.ino)
                    if (!response.isSuccessful || response.body() == null) {
                        // Close the (error) body so the streaming connection is
                        // returned to the pool instead of leaking a socket/fd on
                        // every failed attempt (e.g. 401 after token expiry).
                        response.errorBody()?.close()
                        response.body()?.close()
                        throw Exception("HTTP ${response.code()}: Failed to download $fileName")
                    }

                    response.body()?.use { body ->
                        partPath.outputStream().buffered().use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var bytesRead: Int

                                while (input.read(buffer).also { bytesRead = it } > 0) {
                                    currentCoroutineContext().ensureActive()
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead

                                    // Throttled progress updates (time + byte delta)
                                    val now = System.currentTimeMillis()
                                    val bytesDelta = downloadedBytes - lastPersistedBytes
                                    val timeDelta = now - lastPersistedAt

                                    if (shouldPersistProgress(bytesDelta, timeDelta)) {
                                        download = download.copy(downloadedBytes = downloadedBytes)
                                        downloadItemDao.upsert(download.toEntity())
                                        onProgress(download.id, downloadedBytes, totalBytes)
                                        lastPersistedBytes = downloadedBytes
                                        lastPersistedAt = now
                                    }
                                }
                            }
                        }
                    }

                    // Atomic rename .part → final
                    if (finalPath.exists()) finalPath.delete()
                    val renamed = partPath.renameTo(finalPath)
                    if (!renamed) {
                        throw Exception("Failed to finalize $fileName")
                    }
                    fileSuccess = true

                    // Always flush progress after each completed file.
                    download = download.copy(downloadedBytes = downloadedBytes)
                    downloadItemDao.upsert(download.toEntity())
                    onProgress(download.id, downloadedBytes, totalBytes)
                    lastPersistedBytes = downloadedBytes
                    lastPersistedAt = System.currentTimeMillis()

                } catch (e: CancellationException) {
                    // The drain worker was stopped (pause/cancel/system kill). Clean
                    // up the partial file and rethrow so cancellation propagates
                    // properly. The DownloadManager facade owns the resulting Room
                    // status; an interrupted item stays Downloading so the next drain
                    // worker resumes it (the engine skips already-finished files).
                    try { partPath.delete() } catch (cleanupError: Exception) {
                        // Ignore cleanup errors - file may already be deleted
                    }
                    throw e
                } catch (e: Exception) {
                    try { partPath.delete() } catch (cleanupError: Exception) {
                        // Ignore cleanup errors - file may already be deleted
                    }
                    downloadedBytes = bytesBeforeAttempt

                    retryCount++
                    if (retryCount < maxRetries) {
                        // Exponential backoff: 10s, 20s, 40s
                        delay(retryBackoffMs(retryCount))
                        // Loop will retry the same file
                    } else {
                        // Fail the entire download
                        download = download.copy(
                            status = DownloadStatus.Failed,
                            errorMessage = "$fileName: ${e.message}",
                            downloadedBytes = downloadedBytes,
                        )
                        downloadItemDao.upsert(download.toEntity())
                        return download
                    }
                }
            }
        }

        // All files downloaded successfully
        download = download.copy(
            status = DownloadStatus.Completed,
            downloadedBytes = maxOf(downloadedBytes, totalBytes),
            completedAt = System.currentTimeMillis(),
        )
        downloadItemDao.upsert(download.toEntity())

        // Update audiobook as downloaded with local path
        val bookEntity = audioBookDao.getById(audioBook.id)
        if (bookEntity != null) {
            audioBookDao.upsert(
                bookEntity.copy(
                    isDownloaded = 1,
                    localPath = downloadDir.absolutePath,
                )
            )
        }

        return download
    }

    /** Fetch full book details (audio file metadata) from the server. */
    suspend fun fetchFullBookDetails(audioBookId: String): AudioBook? {
        return try {
            val response = api.getItem(audioBookId, expanded = 1)
            if (response.isSuccessful) {
                response.body()?.let { apiItem ->
                    val audioFiles = apiItem.media?.audioFiles?.mapIndexed { idx, af ->
                        com.ninelivesaudio.app.domain.model.AudioFile(
                            id = af.ino ?: "",
                            ino = af.ino ?: "",
                            index = idx,
                            duration = (af.duration ?: 0.0).seconds,
                            filename = af.metadata?.filename ?: "track_${idx + 1}",
                            size = af.metadata?.size ?: 0,
                        )
                    } ?: emptyList()

                    audioBookDao.getById(audioBookId)?.toDomain()?.copy(audioFiles = audioFiles)
                }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    // ─── File Paths ──────────────────────────────────────────────────────────

    /** Get download directory for an audiobook. */
    private fun getDownloadPath(audioBook: AudioBook): File {
        val basePath = getBasePath()
        val folderName = downloadFolderName(audioBook.author, audioBook.title, audioBook.id)
        return File(basePath, folderName)
    }

    /** Base storage directory for all downloads. */
    private fun getBasePath(): File {
        // Respect user-configured path when possible, with path traversal validation.
        val configuredPath = settingsManager.currentSettings.downloadPath.trim()
        if (configuredPath.isNotEmpty()) {
            try {
                val candidate = File(configuredPath).canonicalFile
                // Reject paths targeting sensitive system directories
                val forbidden = listOf("/system", "/data/data", "/data/user", "/proc", "/dev")
                val isSafe = forbidden.none { candidate.absolutePath.startsWith(it) }
                if (isSafe) {
                    return candidate.also { it.mkdirs() }
                }
                Log.w(TAG, "getBasePath: Configured path rejected (targets system dir): $configuredPath")
            } catch (e: Exception) {
                Log.w(TAG, "getBasePath: Failed to resolve configured path: $configuredPath", e)
            }
        }

        // Fallback to app-specific external storage.
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(musicDir, "Audiobookshelf").also { it.mkdirs() }
    }
}
