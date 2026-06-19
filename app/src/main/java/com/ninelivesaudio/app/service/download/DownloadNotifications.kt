package com.ninelivesaudio.app.service.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notifications for the downloads foreground service.
 *
 * While downloading, the drain worker shows an ongoing FGS notification
 * ([NOTIFICATION_ID]) with determinate progress and Pause / Cancel actions, and
 * keeps it current by re-calling setForeground (updating an FGS notification via
 * a bare notify() does not reliably refresh it). When the queue is paused, a
 * separate standalone notification ([PAUSED_NOTIFICATION_ID]) offers Resume /
 * Cancel. Notification updates are best-effort: if POST_NOTIFICATIONS is denied
 * the foreground service still runs, the notification just is not shown.
 */
object DownloadNotifications {
    const val CHANNEL_ID = "downloads"
    const val NOTIFICATION_ID = 4711
    const val PAUSED_NOTIFICATION_ID = 4712
    private const val CHANNEL_NAME = "Downloads"

    const val ACTION_PAUSE = "com.ninelivesaudio.app.action.DOWNLOAD_PAUSE"
    const val ACTION_RESUME = "com.ninelivesaudio.app.action.DOWNLOAD_RESUME"
    const val ACTION_CANCEL = "com.ninelivesaudio.app.action.DOWNLOAD_CANCEL"

    private const val REQ_PAUSE = 1
    private const val REQ_RESUME = 2
    private const val REQ_CANCEL = 3

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

    /** Ongoing download notification with determinate progress + Pause/Cancel. */
    fun downloadNotification(context: Context, title: String, downloaded: Long, total: Long): Notification {
        val indeterminate = total <= 0
        val percent = if (total > 0) {
            ((downloaded.toDouble() / total) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
        return baseBuilder(context)
            .setContentTitle("Downloading $title")
            .setContentText(if (indeterminate) "Starting..." else "$percent%")
            .setProgress(100, percent, indeterminate)
            .addAction(0, "Pause", broadcast(context, ACTION_PAUSE, REQ_PAUSE))
            .addAction(0, "Cancel", broadcast(context, ACTION_CANCEL, REQ_CANCEL))
            .build()
    }

    /** Standalone "paused" notification with Resume/Cancel. */
    fun showPaused(context: Context, title: String) {
        val notification = baseBuilder(context)
            .setContentTitle("Downloads paused")
            .setContentText(title.ifBlank { "Tap Resume to continue" })
            .addAction(0, "Resume", broadcast(context, ACTION_RESUME, REQ_RESUME))
            .addAction(0, "Cancel", broadcast(context, ACTION_CANCEL, REQ_CANCEL))
            .build()
        notify(context, PAUSED_NOTIFICATION_ID, notification)
    }

    /** Remove the standalone paused notification (e.g. on resume). */
    fun clearPaused(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(PAUSED_NOTIFICATION_ID)
        } catch (_: Exception) {
            // Ignore; nothing showing.
        }
    }

    private fun baseBuilder(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied; the foreground service keeps running.
        }
    }
}
