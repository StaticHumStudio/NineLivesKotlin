package com.ninelivesaudio.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ninelivesaudio.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service for audio playback.
 * Provides:
 * - Media notification with playback controls (play/pause, skip, seek)
 * - Lock screen controls via MediaSession
 * - Audio focus management
 * - Survives app backgrounding
 *
 * Media3 handles most of the notification/session plumbing automatically
 * when we extend MediaSessionService.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    companion object {
        const val CHANNEL_ID = "nine_lives_playback"
        const val NOTIFICATION_ID = 1
    }

    @Inject
    lateinit var playbackManager: PlaybackManager

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Create MediaSession from PlaybackManager's ExoPlayer
        val player = playbackManager.getPlayer()
        if (player != null) {
            mediaSession = MediaSession.Builder(this, player)
                .setSessionActivity(createPendingIntent())
                .build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        // Only release the MediaSession — PlaybackManager owns the ExoPlayer
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Recreate the session if PlaybackManager creates a new player (e.g., loading a new book).
     */
    fun updateSession() {
        val player = playbackManager.getPlayer() ?: return
        val existingSession = mediaSession
        if (existingSession != null && existingSession.player == player) return

        existingSession?.release()
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(createPendingIntent())
            .build()
    }

    private fun createPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Playback",
            NotificationManager.IMPORTANCE_LOW, // Low = no sound, shows in shade
        ).apply {
            description = "Nine Lives Audio playback controls"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
