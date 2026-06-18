package com.ninelivesaudio.app.service.download

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
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
 * Drains the download queue: promotes to a dataSync foreground service, then
 * loops, picking the next downloadable item from Room and running the engine on
 * it until the queue is empty. One worker handles the whole queue, so downloads
 * are strictly sequential (a plain loop, not a WorkManager dependency chain) and
 * a single foreground service covers the whole batch.
 *
 * Plain [CoroutineWorker] instantiated by WorkManager's default factory; it pulls
 * its singletons through a Hilt [EntryPoint] (no androidx.hilt:hilt-work needed).
 */
class DownloadQueueWorker(
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
        android.util.Log.d(TAG, "drain START")
        DownloadNotifications.ensureChannel(applicationContext)

        val dao = deps.downloadItemDao()
        val books = deps.audioBookDao()
        val engine = deps.downloadEngine()
        val manager = deps.downloadManager()

        while (true) {
            val active = dao.getDownloadable().map { it.toDomain() }
            val item = selectNextDownload(active) ?: break
            android.util.Log.d(TAG, "next id=${item.id} status=${item.status} title=${item.title}")

            val bookEntity = books.getById(item.audioBookId)
            if (bookEntity == null) {
                // No audiobook to download; mark Failed so we don't loop on it.
                android.util.Log.d(TAG, "book missing for id=${item.id} -> Failed")
                dao.upsert(
                    item.copy(
                        status = DownloadStatus.Failed,
                        errorMessage = "Audiobook not found",
                    ).toEntity()
                )
                continue
            }
            val book = bookEntity.toDomain()

            // Promote to / keep the foreground service. The first call starts the
            // FGS for the whole batch; later calls just update the notification, so
            // there is never a second foreground-service start mid-queue.
            setForeground(foregroundInfo(item.title, item.downloadedBytes, item.totalBytes))

            // Stream on IO: the engine does blocking network reads and file writes.
            val result = withContext(Dispatchers.IO) {
                engine.download(item, book) { id, downloaded, total ->
                    manager.publishProgress(id, downloaded, total)
                    DownloadNotifications.update(applicationContext, item.title, downloaded, total)
                }
            }
            manager.notifyTerminal(result)
            android.util.Log.d(TAG, "done id=${item.id} result=${result.status}")
        }

        android.util.Log.d(TAG, "drain END (queue empty)")
        return Result.success()
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

    companion object {
        private const val TAG = "DownloadQueueWorker"
    }
}
