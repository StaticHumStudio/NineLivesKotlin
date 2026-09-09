package com.ninelivesaudio.app.service.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.service.DownloadManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the download notification's action buttons. Pause/Resume act on the
 * whole queue; Cancel drops the current book (the one the notification is
 * showing) and lets the queue continue.
 *
 * Plain BroadcastReceiver; it resolves its dependencies through a Hilt
 * [EntryPoint] and does its Room/WorkManager work on a goAsync() coroutine.
 */
class DownloadActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun downloadManager(): DownloadManager
        fun downloadItemDao(): DownloadItemDao
        fun apiService(): ApiService
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    DownloadNotifications.ACTION_PAUSE -> deps.downloadManager().pauseQueue()
                    DownloadNotifications.ACTION_RESUME -> deps.downloadManager().resumeQueue()
                    DownloadNotifications.ACTION_CANCEL -> {
                        // Cancel the book the notification is showing: the current
                        // active item (same selection the drain worker uses).
                        val scope = deps.apiService().captureActiveRemoteScope() ?: return@launch
                        val current = selectNotificationCancelDownload(
                            deps.downloadItemDao()
                                .getDownloadableForOwner(scope.idPrefix)
                                .map { it.toDomain() }
                                .filter {
                                    scope.decodeForEgress(it.id) != null &&
                                        scope.decodeForEgress(it.audioBookId) != null
                                },
                            scope.idPrefix,
                        )
                        if (current != null) deps.downloadManager().cancelDownload(current.id, scope)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

internal fun selectNotificationCancelDownload(
    candidates: List<DownloadItem>,
    remotePrefix: String,
): DownloadItem? = selectNextDownload(
    candidates.filter { item ->
        ownerScopedDownloadIdsMatch(remotePrefix, item.id, item.audioBookId)
    },
)
