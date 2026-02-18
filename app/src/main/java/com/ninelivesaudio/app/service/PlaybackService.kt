package com.ninelivesaudio.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ninelivesaudio.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for audio playback and Android Auto browsing.
 * Extends Media3 MediaLibraryService (which itself extends MediaSessionService)
 * so it automatically handles:
 * - Media notification with playback controls (play/pause, skip, seek)
 * - Lock screen controls via MediaSession
 * - Android Auto browse tree + playback
 * - Foreground service lifecycle (startForeground/stopForeground)
 *
 * The MediaSession is owned by PlaybackManager and retrieved via getMediaSession().
 * This keeps the service thin — all playback logic lives in PlaybackManager.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "PlaybackService"
        const val CHANNEL_ID = "nine_lives_playback"
        const val NOTIFICATION_ID = 1
    }

    @Inject
    lateinit var playbackManager: PlaybackManager

    @Inject
    lateinit var mediaBrowseTree: MediaBrowseTree

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return playbackManager.getMediaSession() as? MediaLibrarySession
    }

    /**
     * Create a [MediaLibrarySession.Callback] that handles Android Auto browse requests.
     * Called by PlaybackManager when building the MediaLibrarySession.
     */
    fun createLibraryCallback(): MediaLibrarySession.Callback {
        return LibraryCallback()
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
        serviceScope.cancel()
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

    // ═════════════════════════════════════════════════════════════════════
    //  MediaLibrarySession.Callback — Android Auto browse tree
    // ═════════════════════════════════════════════════════════════════════

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(MediaBrowseTree.ROOT_ID)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle("Nine Lives Audio")
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        @OptIn(UnstableApi::class)
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future(Dispatchers.IO) {
                try {
                    val children = mediaBrowseTree.getChildren(parentId, page, pageSize)
                    LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
                } catch (e: Exception) {
                    Log.e(TAG, "onGetChildren($parentId) failed: ${e.message}", e)
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
                }
            }
        }

        @OptIn(UnstableApi::class)
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return serviceScope.future(Dispatchers.IO) {
                try {
                    val item = mediaBrowseTree.getItem(mediaId)
                    if (item != null) {
                        LibraryResult.ofItem(item, /* params= */ null)
                    } else {
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onGetItem($mediaId) failed: ${e.message}", e)
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
                }
            }
        }

        @OptIn(UnstableApi::class)
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            // Trigger async search; results delivered via onGetSearchResult
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val results = mediaBrowseTree.search(query)
                    session.notifySearchResultChanged(browser, query, results.size, params)
                } catch (e: Exception) {
                    Log.e(TAG, "onSearch($query) failed: ${e.message}", e)
                }
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        @OptIn(UnstableApi::class)
        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future(Dispatchers.IO) {
                try {
                    val results = mediaBrowseTree.search(query)
                        .drop(page * pageSize)
                        .take(pageSize)
                    LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
                } catch (e: Exception) {
                    Log.e(TAG, "onGetSearchResult($query) failed: ${e.message}", e)
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
                }
            }
        }

        /**
         * Called when Android Auto (or any controller) requests to play a media item.
         * We intercept the request, extract the book ID, and load it via PlaybackManager.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Try to resolve the first item's media ID to a book
            val firstItem = mediaItems.firstOrNull()
            val mediaId = firstItem?.mediaId

            if (mediaId != null) {
                val bookId = mediaBrowseTree.extractBookId(mediaId)
                if (bookId != null) {
                    // Load the book through PlaybackManager (it handles its own threading)
                    serviceScope.launch {
                        try {
                            playbackManager.loadBookById(bookId)
                        } catch (e: Exception) {
                            Log.e(TAG, "onSetMediaItems: failed to load book $bookId: ${e.message}", e)
                        }
                    }
                    // Return empty to prevent default handling — we're handling playback ourselves
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            emptyList(), startIndex, startPositionMs
                        )
                    )
                }
            }

            // Fallback: let the default handler deal with it
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }
    }
}
