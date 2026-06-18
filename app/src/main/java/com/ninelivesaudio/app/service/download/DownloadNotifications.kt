package com.ninelivesaudio.app.service.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Ongoing notification for the downloads foreground service. The worker shows
 * one notification (a single FGS for the whole sequential queue) and updates its
 * determinate progress as bytes arrive. Updates are best-effort: if the user
 * denied POST_NOTIFICATIONS the foreground service still runs, the notification
 * just will not be visible.
 */
object DownloadNotifications {
    const val CHANNEL_ID = "downloads"
    const val NOTIFICATION_ID = 4711
    private const val CHANNEL_NAME = "Downloads"

    /** Create the (low-importance, silent) downloads channel. Idempotent. */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing audiobook downloads"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Build the ongoing download notification with determinate progress. */
    fun build(context: Context, title: String, downloaded: Long, total: Long): Notification {
        val indeterminate = total <= 0
        val percent = if (total > 0) {
            ((downloaded.toDouble() / total) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $title")
            .setContentText(if (indeterminate) "Starting..." else "$percent%")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Update the ongoing notification. No-op if notifications are not permitted. */
    fun update(context: Context, title: String, downloaded: Long, total: Long) {
        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, build(context, title, downloaded, total))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied; the foreground service keeps running.
        }
    }
}
