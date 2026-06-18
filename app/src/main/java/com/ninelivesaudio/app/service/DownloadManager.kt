package com.ninelivesaudio.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.service.download.DOWNLOAD_WORK_NAME
import com.ninelivesaudio.app.service.download.DownloadEngine
import com.ninelivesaudio.app.service.download.DownloadWorker
import com.ninelivesaudio.app.service.download.KEY_DOWNLOAD_ID
import com.ninelivesaudio.app.service.download.downloadWorkTag
import com.ninelivesaudio.app.service.download.estimateTotalBytes
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
 * Execution and concurrency are owned by WorkManager: every book runs as a
 * [DownloadWorker] on a single unique-work chain ([DOWNLOAD_WORK_NAME]), which
 * makes downloads strictly sequential and lets them survive app-switch and
 * process death. The streaming work itself lives in [DownloadEngine]; this class
 * enqueues it and owns the event flows the UI overlays on top of Room.
 */
@Singleton
class DownloadManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadItemDao: DownloadItemDao,
    private val audioBookDao: AudioBookDao,
    private val engine: DownloadEngine,
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

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
     * Creates a DownloadItem, persists it, and enqueues the download worker.
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

        // Persist to DB, then enqueue the worker.
        downloadItemDao.upsert(downloadItem.toEntity())
        enqueue(downloadId)

        return downloadItem
    }

    /** Pause an active download: stop its worker and mark it Paused. */
    suspend fun pauseDownload(downloadId: String) {
        workManager.cancelAllWorkByTag(downloadWorkTag(downloadId))

        val entity = downloadItemDao.getById(downloadId) ?: return
        downloadItemDao.upsert(entity.copy(status = DownloadStatus.Paused.ordinal))
    }

    /** Resume a paused download by re-enqueuing it at the back of the queue. */
    suspend fun resumeDownload(downloadId: String) {
        val entity = downloadItemDao.getById(downloadId) ?: return
        val item = entity.toDomain()
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        // Reset to Queued. The engine skips already-finished files on re-run.
        downloadItemDao.upsert(entity.copy(status = DownloadStatus.Queued.ordinal))
        enqueue(downloadId)
    }

    /** Cancel a download and clean up. */
    suspend fun cancelDownload(downloadId: String) {
        workManager.cancelAllWorkByTag(downloadWorkTag(downloadId))
        downloadItemDao.deleteById(downloadId)
    }

    /** Delete a completed download's files and DB record. */
    suspend fun deleteDownload(audioBookId: String) {
        // Use the actual localPath stored on the audiobook — this matches
        // the path set by the engine. The old code used getDownloadPath(audioBookId)
        // which returns basePath/audioBookId, but downloads are saved to basePath/Author - Title.
        val bookEntity = audioBookDao.getById(audioBookId)

        // Stop any in-flight or queued work for this book before deleting files.
        val downloadEntity = downloadItemDao.getByAudioBookId(audioBookId)
        if (downloadEntity != null) {
            workManager.cancelAllWorkByTag(downloadWorkTag(downloadEntity.id))
        }

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
        if (downloadEntity != null) {
            downloadItemDao.deleteById(downloadEntity.id)
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

    /** Emit a terminal event for the worker's finished download. */
    fun notifyTerminal(item: DownloadItem) {
        when (item.status) {
            DownloadStatus.Completed -> _downloadCompleted.tryEmit(item)
            DownloadStatus.Failed -> _downloadFailed.tryEmit(item)
            else -> { /* Paused/cancelled: no terminal event */ }
        }
    }

    // ─── Enqueue ─────────────────────────────────────────────────────────────

    /**
     * Enqueue (or append) a book's worker onto the shared sequential chain.
     * APPEND_OR_REPLACE on one unique name is what enforces strict one-at-a-time
     * ordering across the whole queue.
     */
    private fun enqueue(downloadId: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(KEY_DOWNLOAD_ID to downloadId))
            .addTag(downloadWorkTag(downloadId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        workManager.enqueueUniqueWork(DOWNLOAD_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
