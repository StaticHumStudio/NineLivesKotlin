package com.ninelivesaudio.app.service

import android.util.Log
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.service.download.DownloadEngine
import com.ninelivesaudio.app.service.download.estimateTotalBytes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public facade for audiobook downloads: queue/pause/resume/cancel/delete and
 * progress/completion events. The streaming work itself lives in
 * [DownloadEngine]; this class owns queuing, concurrency, and the event flows
 * the UI collects.
 *
 * Port of C# DownloadService.cs. Uses coroutines + Semaphore instead of
 * ConcurrentDictionary + SemaphoreSlim.
 */
@Singleton
class DownloadManager @Inject constructor(
    private val downloadItemDao: DownloadItemDao,
    private val audioBookDao: AudioBookDao,
    private val engine: DownloadEngine,
) {
    companion object {
        private const val TAG = "DownloadManager"
        private const val MAX_CONCURRENT = 2
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

    private val _progressUpdates = MutableSharedFlow<DownloadProgress>(replay = 1, extraBufferCapacity = 64)
    val progressUpdates: SharedFlow<DownloadProgress> = _progressUpdates.asSharedFlow()

    private val _downloadCompleted = MutableSharedFlow<DownloadItem>(replay = 1, extraBufferCapacity = 8)
    val downloadCompleted: SharedFlow<DownloadItem> = _downloadCompleted.asSharedFlow()

    private val _downloadFailed = MutableSharedFlow<DownloadItem>(replay = 1, extraBufferCapacity = 8)
    val downloadFailed: SharedFlow<DownloadItem> = _downloadFailed.asSharedFlow()

    // ─── Queue Operations ────────────────────────────────────────────────────

    /**
     * Queue an audiobook for download.
     * Creates a DownloadItem, persists it, and starts the download process.
     *
     * Scanned-local books carry a synthetic id the server doesn't know, so they
     * are short-circuited here (returns null) rather than attempting a fetch
     * that would 404 against ABS.
     */
    suspend fun queueDownload(audioBook: AudioBook): DownloadItem? {
        if (audioBook.isLocal) {
            Log.d(TAG, "Skipping download for local book ${audioBook.id} (${audioBook.title})")
            return null
        }
        val downloadId = UUID.randomUUID().toString()

        // Ensure we have file metadata before queuing.
        val resolvedBook = if (audioBook.audioFiles.isEmpty()) {
            engine.fetchFullBookDetails(audioBook.id) ?: audioBook
        } else {
            audioBook
        }

        val files = resolvedBook.audioFiles.mapNotNull { it.ino.takeIf { ino -> ino.isNotBlank() } }
        if (files.isEmpty()) {
            val failedItem = DownloadItem(
                id = downloadId,
                audioBookId = audioBook.id,
                title = audioBook.title,
                status = DownloadStatus.Failed,
                errorMessage = "No downloadable audio files found for this book",
                startedAt = System.currentTimeMillis(),
            )
            downloadItemDao.upsert(failedItem.toEntity())
            _downloadFailed.tryEmit(failedItem)
            return failedItem
        }

        val downloadItem = DownloadItem(
            id = downloadId,
            audioBookId = audioBook.id,
            title = audioBook.title,
            status = DownloadStatus.Queued,
            totalBytes = estimateTotalBytes(resolvedBook.audioFiles),
            downloadedBytes = 0,
            startedAt = System.currentTimeMillis(),
            filesToDownload = files,
        )

        // Persist to DB
        downloadItemDao.upsert(downloadItem.toEntity())

        // Start the download
        launchDownload(downloadItem, resolvedBook)

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
        // the path set by the engine. The old code used getDownloadPath(audioBookId)
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
        // Start lazily so we can atomically claim the activeJobs slot BEFORE the
        // coroutine runs. Two quick resume/queue calls for the same id would
        // otherwise both run the engine, writing the same .part file and
        // orphaning the first (uncancellable) job. putIfAbsent makes the claim race-free.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                semaphore.acquire()
                runDownload(item, audioBook)
            } finally {
                semaphore.release()
                activeJobs.remove(item.id)
            }
        }
        val existing = activeJobs.putIfAbsent(item.id, job)
        if (existing != null) {
            // Another job for this id is already active — drop this duplicate.
            job.cancel()
            Log.w(TAG, "launchDownload: download ${item.id} already active, ignoring duplicate")
        } else {
            job.start()
        }
    }

    /** Run the engine for one book and surface its terminal status as an event. */
    private suspend fun runDownload(item: DownloadItem, audioBook: AudioBook) {
        val result = engine.download(item, audioBook) { id, downloaded, total ->
            emitProgress(id, downloaded, total)
        }
        when (result.status) {
            DownloadStatus.Completed -> _downloadCompleted.tryEmit(result)
            DownloadStatus.Failed -> _downloadFailed.tryEmit(result)
            else -> { /* Paused/cancelled: no terminal event */ }
        }
    }

    private fun emitProgress(downloadId: String, downloaded: Long, total: Long) {
        val progress = if (total > 0) (downloaded.toDouble() / total * 100.0).coerceIn(0.0, 100.0) else 0.0
        _progressUpdates.tryEmit(
            DownloadProgress(
                downloadId = downloadId,
                progress = progress,
                downloadedBytes = downloaded,
                totalBytes = total,
            )
        )
    }
}
