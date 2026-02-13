package com.ninelivesaudio.app.service

import android.content.Context
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.data.remote.AudiobookshelfApi
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlin.time.Duration.Companion.seconds
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Manages audiobook downloads with concurrent queue, pause/resume, retry with
 * exponential backoff, and progress tracking.
 *
 * Port of C# DownloadService.cs (379 lines).
 * Uses coroutines + Semaphore instead of ConcurrentDictionary + SemaphoreSlim.
 */
@Singleton
class DownloadManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadItemDao: DownloadItemDao,
    private val audioBookDao: AudioBookDao,
    private val api: AudiobookshelfApi,
    private val settingsManager: SettingsManager,
) {
    companion object {
        private const val MAX_CONCURRENT = 2
        private const val BUFFER_SIZE = 81_920 // 80 KB
        private const val PROGRESS_UPDATE_INTERVAL = 512 * 1024 // Every 512 KB
    }

    // Coroutine scope for download tasks
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Semaphore for concurrency control
    private val semaphore = Semaphore(MAX_CONCURRENT)

    // Active download jobs (downloadId → Job)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // ─── Progress Events ─────────────────────────────────────────────────────

    data class DownloadProgress(
        val downloadId: String,
        val progress: Double, // 0-100
        val downloadedBytes: Long,
        val totalBytes: Long,
    )

    private val _progressUpdates = MutableSharedFlow<DownloadProgress>(replay = 0, extraBufferCapacity = 64)
    val progressUpdates: SharedFlow<DownloadProgress> = _progressUpdates.asSharedFlow()

    private val _downloadCompleted = MutableSharedFlow<DownloadItem>(replay = 0, extraBufferCapacity = 8)
    val downloadCompleted: SharedFlow<DownloadItem> = _downloadCompleted.asSharedFlow()

    private val _downloadFailed = MutableSharedFlow<DownloadItem>(replay = 0, extraBufferCapacity = 8)
    val downloadFailed: SharedFlow<DownloadItem> = _downloadFailed.asSharedFlow()

    // ─── Queue Operations ────────────────────────────────────────────────────

    /**
     * Queue an audiobook for download.
     * Creates a DownloadItem, persists it, and starts the download process.
     */
    suspend fun queueDownload(audioBook: AudioBook): DownloadItem {
        val downloadId = UUID.randomUUID().toString()
        val files = audioBook.audioFiles.map { it.ino }

        val downloadItem = DownloadItem(
            id = downloadId,
            audioBookId = audioBook.id,
            title = audioBook.title,
            status = DownloadStatus.Queued,
            totalBytes = audioBook.audioFiles.sumOf { it.size }.let { size ->
                if (size > 0) size
                else (audioBook.audioFiles.sumOf { it.duration.inWholeSeconds } * 16_000L) // ~128kbps estimate
            },
            downloadedBytes = 0,
            startedAt = System.currentTimeMillis(),
            filesToDownload = files,
        )

        // Persist to DB
        downloadItemDao.upsert(downloadItem.toEntity())

        // Start the download
        launchDownload(downloadItem, audioBook)

        return downloadItem
    }

    /** Pause an active download. */
    suspend fun pauseDownload(downloadId: String) {
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)

        val entity = downloadItemDao.getById(downloadId) ?: return
        downloadItemDao.upsert(entity.copy(status = DownloadStatus.Paused.ordinal))
    }

    /** Resume a paused download. */
    suspend fun resumeDownload(downloadId: String) {
        val entity = downloadItemDao.getById(downloadId) ?: return
        val item = entity.toDomain()
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        // Reset to Queued
        downloadItemDao.upsert(entity.copy(status = DownloadStatus.Queued.ordinal))

        // Reload audiobook to get file info
        val bookEntity = audioBookDao.getById(item.audioBookId) ?: return
        val audioBook = bookEntity.toDomain()

        launchDownload(item.copy(status = DownloadStatus.Queued), audioBook)
    }

    /** Cancel a download and clean up. */
    suspend fun cancelDownload(downloadId: String) {
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)

        downloadItemDao.deleteById(downloadId)
    }

    /** Delete a completed download's files and DB record. */
    suspend fun deleteDownload(audioBookId: String) {
        // Use the actual localPath stored on the audiobook — this matches
        // the path set by processDownload(). The old code used getDownloadPath(audioBookId)
        // which returns basePath/audioBookId, but downloads are saved to basePath/Author - Title.
        val bookEntity = audioBookDao.getById(audioBookId)
        withContext(Dispatchers.IO) {
            val localPath = bookEntity?.localPath
            if (!localPath.isNullOrEmpty()) {
                File(localPath).deleteRecursively()
            }
        }

        // Update audiobook as not downloaded
        if (bookEntity != null) {
            audioBookDao.upsert(bookEntity.copy(isDownloaded = 0, localPath = null))
        }

        // Delete download record
        val downloadEntity = downloadItemDao.getByAudioBookId(audioBookId)
        if (downloadEntity != null) {
            downloadItemDao.deleteById(downloadEntity.id)
        }
    }

    /** Check if a book is downloaded. */
    suspend fun isBookDownloaded(audioBookId: String): Boolean {
        val entity = downloadItemDao.getByAudioBookId(audioBookId)
        return entity?.status == DownloadStatus.Completed.ordinal
    }

    // ─── Download Processing ─────────────────────────────────────────────────

    private fun launchDownload(item: DownloadItem, audioBook: AudioBook) {
        val job = scope.launch {
            try {
                semaphore.acquire()
                processDownload(item, audioBook)
            } finally {
                semaphore.release()
                activeJobs.remove(item.id)
            }
        }
        activeJobs[item.id] = job
    }

    private suspend fun processDownload(item: DownloadItem, audioBook: AudioBook) {
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

        // Calculate total bytes
        val totalBytes = book.audioFiles.sumOf { it.size }.let { size ->
            if (size > 0) size
            else (book.audioFiles.sumOf { it.duration.inWholeSeconds } * 16_000L)
        }
        download = download.copy(totalBytes = totalBytes)

        var downloadedBytes = 0L
        val maxRetries = item.maxRetries

        // Download each audio file
        for (i in book.audioFiles.indices) {
            val audioFile = book.audioFiles[i]

            // Check for cancellation
            currentCoroutineContext().ensureActive()

            val fileName = sanitizeFileName(audioFile.filename.ifEmpty { "track_${i + 1}" })
            val finalPath = File(downloadDir, fileName)
            val partPath = File(downloadDir, "$fileName.part")

            // Skip if already downloaded
            if (finalPath.exists() && finalPath.length() > 0) {
                downloadedBytes += finalPath.length()
                emitProgress(download.id, downloadedBytes, totalBytes)
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
                    val response = api.getAudioFileStream(audioBook.id, audioFile.ino)
                    if (!response.isSuccessful || response.body() == null) {
                        throw Exception("HTTP ${response.code()}: Failed to download $fileName")
                    }

                    response.body()!!.use { body ->
                        partPath.outputStream().buffered().use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var bytesRead: Int

                                while (input.read(buffer).also { bytesRead = it } > 0) {
                                    currentCoroutineContext().ensureActive()
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead

                                    // Throttled progress updates
                                    if (downloadedBytes % PROGRESS_UPDATE_INTERVAL < BUFFER_SIZE) {
                                        download = download.copy(downloadedBytes = downloadedBytes)
                                        downloadItemDao.upsert(download.toEntity())
                                        emitProgress(download.id, downloadedBytes, totalBytes)
                                    }
                                }
                            }
                        }
                    }

                    // Atomic rename .part → final
                    if (finalPath.exists()) finalPath.delete()
                    partPath.renameTo(finalPath)
                    fileSuccess = true

                } catch (e: CancellationException) {
                    // Clean up partial file
                    try { partPath.delete() } catch (_: Exception) {}
                    // Mark as paused (not failed)
                    download = download.copy(status = DownloadStatus.Paused, downloadedBytes = downloadedBytes)
                    downloadItemDao.upsert(download.toEntity())
                    return
                } catch (e: Exception) {
                    try { partPath.delete() } catch (_: Exception) {}
                    downloadedBytes = bytesBeforeAttempt

                    retryCount++
                    if (retryCount < maxRetries) {
                        // Exponential backoff: 10s, 20s, 40s
                        val delayMs = (2.0.pow(retryCount) * 5_000).toLong()
                        delay(delayMs)
                        // Loop will retry the same file
                    } else {
                        // Fail the entire download
                        download = download.copy(
                            status = DownloadStatus.Failed,
                            errorMessage = "$fileName: ${e.message}",
                            downloadedBytes = downloadedBytes,
                        )
                        downloadItemDao.upsert(download.toEntity())
                        _downloadFailed.tryEmit(download)
                        return
                    }
                }
            }
        }

        // All files downloaded successfully
        download = download.copy(
            status = DownloadStatus.Completed,
            downloadedBytes = totalBytes,
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

        _downloadCompleted.tryEmit(download)
    }

    private suspend fun fetchFullBookDetails(audioBookId: String): AudioBook? {
        return try {
            val response = api.getItem(audioBookId, expanded = 1)
            if (response.isSuccessful) {
                response.body()?.let { apiItem ->
                    // Map to domain model (simplified)
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

    private fun emitProgress(downloadId: String, downloaded: Long, total: Long) {
        val progress = if (total > 0) downloaded.toDouble() / total * 100.0 else 0.0
        _progressUpdates.tryEmit(
            DownloadProgress(
                downloadId = downloadId,
                progress = progress,
                downloadedBytes = downloaded,
                totalBytes = total,
            )
        )
    }

    // ─── File Paths ──────────────────────────────────────────────────────────

    /** Get download directory for an audiobook. */
    private fun getDownloadPath(audioBook: AudioBook): File {
        val basePath = getBasePath()
        val author = audioBook.author.takeIf { it.isNotBlank() && it != "Unknown Author" }
        val folderName = if (author != null) {
            sanitizeFileName("$author - ${audioBook.title}")
        } else {
            sanitizeFileName(audioBook.title)
        }.ifBlank { audioBook.id }

        return File(basePath, folderName)
    }

    /** Base storage directory for all downloads. */
    private fun getBasePath(): File {
        // Use app-specific external storage: /storage/emulated/0/Android/data/package/files/Music/AudioBookshelf/
        val musicDir = context.getExternalFilesDir("Music")
        return File(musicDir, "AudioBookshelf").also { it.mkdirs() }
    }

    /** Sanitize a filename for the filesystem. */
    private fun sanitizeFileName(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200) // Reasonable max filename length
    }
}
