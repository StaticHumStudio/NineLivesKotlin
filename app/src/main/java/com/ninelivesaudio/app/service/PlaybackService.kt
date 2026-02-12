package com.ninelivesaudio.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ninelivesaudio.app.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service for audio playback.
 * Extends Media3 MediaSessionService which automatically handles:
 * - Media notification with playback controls (play/pause, skip, seek)
 * - Lock screen controls via MediaSession
 * - Android Auto integration
 * - Foreground service lifecycle (startForeground/stopForeground)
 *
 * The MediaSession is owned by PlaybackManager and retrieved via getMediaSession().
 * This keeps the service thin — all playback logic lives in PlaybackManager.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "PlaybackService"
        const val CHANNEL_ID = "nine_lives_playback"
        const val NOTIFICATION_ID = 1
    }

    @Inject
    lateinit var playbackManager: PlaybackManager

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: PlaybackService starting")
        createNotificationChannel()

        // Configure Media3's notification provider to use our channel and notification ID.
        // Without this, DefaultMediaNotificationProvider creates its own "default" channel
        // which may not match our channel settings.
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel_name)
            .setNotificationId(NOTIFICATION_ID)
            .build()
        setMediaNotificationProvider(notificationProvider)

        playbackManager.setPlaybackService(this)
        Log.d(TAG, "onCreate: PlaybackService created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val session = playbackManager.getMediaSession()
        if (session == null) {
            Log.w(TAG, "onGetSession: MediaSession is null — player may not be loaded yet")
        }
        return session
    }

    /**
     * Called by PlaybackManager after a new MediaSession is created.
     * Forces Media3 to re-associate the session and refresh the notification.
     * Without this, reloading a book while the service is already running
     * leaves the old (released) session cached.
     */
    @OptIn(UnstableApi::class)
    fun refreshSession(session: MediaSession) {
        Log.d(TAG, "refreshSession: adding session to service")
        addSession(session)
    }

    override fun onDestroy() {
        playbackManager.setPlaybackService(null)
        Log.d(TAG, "onDestroy: PlaybackService destroyed")
        super.onDestroy()
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
