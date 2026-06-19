package com.ninelivesaudio.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.service.download.DOWNLOAD_WORK_NAME
import com.ninelivesaudio.app.service.download.DownloadEngine
import com.ninelivesaudio.app.service.download.DownloadNotifications
import com.ninelivesaudio.app.service.download.DownloadQueueWorker
import com.ninelivesaudio.app.service.download.estimateTotalBytes
import com.ninelivesaudio.app.service.download.selectNextDownload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public facade for audiobook downloads: queue/pause/resume/cancel/delete and
 * progress/completion events.
 *
 * Execution is owned by a single [DownloadQueueWorker] that drains the Room
 * queue one book at a time as a dataSync foreground service, so downloads are
 * strictly sequential and survive app-switch / process death. This class
 * manages the Room queue, (re)enqueues the drain worker, and owns the event
 * flows the UI overlays on top of Room.
 */
@Singleton
class DownloadManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadItemDao: DownloadItemDao,
    private val audioBookDao: AudioBookDao,
    private val engine: DownloadEngine,
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    // Whether the whole download queue is paused (set from the notification's
    // Pause action). In-memory: a paused queue isn't downloading, so the process
    // is a kill candidate and the flag may be lost on death — acceptable, since
    // Resume re-enqueues the drain and queuing/resuming a book restarts it anyway.
    @Volatile
    private var downloadsPaused = false

    /** Whether the drain worker should hold (used by the worker to stop draining). */
    fun isDownloadsPaused(): Boolean = downloadsPaused

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
     * Creates a DownloadItem, persists it Queued, and ensures the drain worker
     * is running so it gets picked up.
     *
     * Scanned-local books carry a synthetic id the server doesn't know, so they
     * are short-circuited here (returns null) rather than attempting a fetch
     * that would 404 against ABS.
     */
    suspend fun queueDownload(audioBook: AudioBook): DownloadItem? {
        if (audioBook.isLocal) {
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

        downloadItemDao.upsert(downloadItem.toEntity())
        // Respect a paused queue: the book waits until the user resumes.
        if (!downloadsPaused) enqueueDrain(replace = false)

        return downloadItem
    }

    /** Pause a download: mark it Paused; restart the drain if it was the active one. */
    suspend fun pauseDownload(downloadId: String) {
        val entity = downloadItemDao.getById(downloadId) ?: return
        val wasDownloading = entity.status == DownloadStatus.Downloading.ordinal
        downloadItemDao.upsert(entity.copy(status = DownloadStatus.Paused.ordinal))
        if (wasDownloading) {
            // Stop the engine on this book and let the drain continue with the rest.
            enqueueDrain(replace = true)
        }
    }

    /** Resume a paused/failed download by re-queuing it and ensuring the drain runs. */
    suspend fun resumeDownload(downloadId: String) {
        val entity = downloadItemDao.getById(downloadId) ?: return
        val item = entity.toDomain()
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        // Reset to Queued. The engine skips already-finished files on re-run.
        downloadItemDao.upsert(entity.copy(status = DownloadStatus.Queued.ordinal))
        if (!downloadsPaused) enqueueDrain(replace = false)
    }

    /** Pause the whole download queue: stop the drain and show a Resume notification. */
    suspend fun pauseQueue() {
        downloadsPaused = true
        // Stop the drain worker; the active book stays Downloading in Room and is
        // resumed first when the queue restarts.
        workManager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
        val current = selectNextDownload(downloadItemDao.getDownloadable().map { it.toDomain() })
        DownloadNotifications.showPaused(context, current?.title ?: "")
    }

    /** Resume the whole download queue. */
    fun resumeQueue() {
        downloadsPaused = false
        DownloadNotifications.clearPaused(context)
        enqueueDrain(replace = false)
    }

    /** Cancel a download and clean up; restart the drain if it was the active one. */
    suspend fun cancelDownload(downloadId: String) {
        val entity = downloadItemDao.getById(downloadId)
        val wasDownloading = entity?.status == DownloadStatus.Downloading.ordinal
        downloadItemDao.deleteById(downloadId)
        if (wasDownloading) {
            enqueueDrain(replace = true)
        }
    }

    /** Delete a download's files and DB record. */
    suspend fun deleteDownload(audioBookId: String) {
        // Use the actual localPath stored on the audiobook — this matches the path
        // set by the engine (basePath/Author - Title), not basePath/audioBookId.
        val bookEntity = audioBookDao.getById(audioBookId)
        val downloadEntity = downloadItemDao.getByAudioBookId(audioBookId)
        val wasDownloading = downloadEntity?.status == DownloadStatus.Downloading.ordinal

        withContext(Dispatchers.IO) {
            val localPath = bookEntity?.localPath
            if (!localPath.isNullOrEmpty()) {
                File(localPath).deleteRecursively()
            }
        }

        if (bookEntity != null) {
            audioBookDao.upsert(bookEntity.copy(isDownloaded = 0, localPath = null))
        }
        if (downloadEntity != null) {
            downloadItemDao.deleteById(downloadEntity.id)
        }
        if (wasDownloading) {
            // Stop the engine if it was mid-download on this book.
            enqueueDrain(replace = true)
        }
    }

    /** Check if a book is downloaded. */
    suspend fun isBookDownloaded(audioBookId: String): Boolean {
        val entity = downloadItemDao.getByAudioBookId(audioBookId)
        return entity?.status == DownloadStatus.Completed.ordinal
    }

    // ─── Worker callbacks ──────────────────────────────────────────────────

    /** Republish live progress from the worker for the UI's liveliness overlay. */
    fun publishProgress(downloadId: String, downloaded: Long, total: Long) {
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

    /** Emit a terminal event for a finished download. */
    fun notifyTerminal(item: DownloadItem) {
        when (item.status) {
            DownloadStatus.Completed -> _downloadCompleted.tryEmit(item)
            DownloadStatus.Failed -> _downloadFailed.tryEmit(item)
            else -> { /* Paused/cancelled: no terminal event */ }
        }
    }

    // ─── Drain worker ────────────────────────────────────────────────────────

    /**
     * Ensure the single download-queue worker is running.
     *
     * [replace] = false (KEEP): used when adding work. If a drain worker is
     * already running it keeps going and picks up the new Queued row on its next
     * loop; otherwise a fresh one starts.
     *
     * [replace] = true (REPLACE): used when pausing/cancelling the active book.
     * It cancels the running drain (stopping the engine on the current book) and
     * starts a fresh drain that skips the now paused/removed item and continues.
     */
    private fun enqueueDrain(replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        android.util.Log.d("DownloadManager", "enqueueDrain policy=${if (replace) "REPLACE" else "KEEP"}")
        workManager.enqueueUniqueWork(DOWNLOAD_WORK_NAME, policy, request)
    }
}
