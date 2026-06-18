package com.ninelivesaudio.app.service.download

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.service.DownloadManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one book's download as a long-running, foreground (dataSync) WorkManager
 * job. All download work shares a single unique-work chain ([DOWNLOAD_WORK_NAME])
 * so it runs strictly one book at a time and survives app-switch / process death.
 *
 * This is a plain [CoroutineWorker] instantiated by WorkManager's default
 * factory; it pulls its singletons through a Hilt [EntryPoint] rather than via
 * @HiltWorker, so no androidx.hilt:hilt-work dependency or Configuration.Provider
 * wiring is needed.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun downloadEngine(): DownloadEngine
        fun downloadItemDao(): DownloadItemDao
        fun audioBookDao(): AudioBookDao
        fun downloadManager(): DownloadManager
    }

    private val deps: Deps by lazy {
        EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
    }

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()

        val itemEntity = deps.downloadItemDao().getById(downloadId)
            ?: return Result.success() // already removed (cancelled/deleted): nothing to do
        val item = itemEntity.toDomain()

        // Honor a pause/cancel that landed before this worker started running.
        if (item.status == DownloadStatus.Paused || item.status == DownloadStatus.Cancelled) {
            return Result.success()
        }

        val bookEntity = deps.audioBookDao().getById(item.audioBookId) ?: return Result.failure()
        val book = bookEntity.toDomain()

        DownloadNotifications.ensureChannel(applicationContext)
        setForeground(foregroundInfo(item.title, item.downloadedBytes, item.totalBytes))

        // Stream on the IO dispatcher: the engine does blocking network reads and
        // file writes, which must not run on the CPU-bound default worker pool.
        val result = withContext(Dispatchers.IO) {
            deps.downloadEngine().download(item, book) { id, downloaded, total ->
                deps.downloadManager().publishProgress(id, downloaded, total)
                DownloadNotifications.update(applicationContext, item.title, downloaded, total)
            }
        }

        deps.downloadManager().notifyTerminal(result)

        return when (result.status) {
            DownloadStatus.Completed -> Result.success()
            // Paused means the user cancelled this work; not a failure to retry.
            DownloadStatus.Paused -> Result.success()
            else -> Result.failure()
        }
    }

    private fun foregroundInfo(title: String, downloaded: Long, total: Long): ForegroundInfo {
        val notification = DownloadNotifications.build(applicationContext, title, downloaded, total)
        // minSdk is 30, so the typed foreground service is always available.
        return ForegroundInfo(
            DownloadNotifications.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
