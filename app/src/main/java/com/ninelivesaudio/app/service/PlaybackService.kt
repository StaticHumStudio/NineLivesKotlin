package com.ninelivesaudio.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Process
import android.util.Log
import androidx.annotation.VisibleForTesting
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
        private val ANDROID_AUTO_PACKAGES = setOf(
            "com.google.android.projection.gearhead", // Android Auto on phones
            "com.google.android.apps.automotive.media", // Android Automotive media host
        )

        @VisibleForTesting
        internal fun isTrustedController(
            appPackageName: String,
            controllerPackageName: String,
            controllerUid: Int,
            packageUidVerifier: (packageName: String, uid: Int) -> Boolean,
        ): Boolean {
            if (!packageUidVerifier(controllerPackageName, controllerUid)) {
                return false
            }

            if (controllerUid == Process.SYSTEM_UID) {
                return true
            }

            return controllerPackageName == appPackageName ||
                controllerPackageName in ANDROID_AUTO_PACKAGES
        }
    }

    @Inject
    lateinit var playbackManager: PlaybackManager

    @Inject
    lateinit var mediaBrowseTree: MediaBrowseTree

    @Inject
    lateinit var sleepTimerManager: SleepTimerManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var shakeDetector: ShakeDetector? = null

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

        // Create persistent MediaLibrarySession so Android Auto can browse
        // even before any book is loaded from the phone
        playbackManager.initSession()

        // Create shake detector and wire to SleepTimerManager
        shakeDetector = ShakeDetector(
            context = this,
            onShake = { sleepTimerManager.onShakeDetected() },
            onMotionUpdate = { moving -> sleepTimerManager.onMotionUpdate(moving) },
        )

        // Register/unregister accelerometer based on sleep timer state
        serviceScope.launch {
            sleepTimerManager.state.collect { state ->
                if (state.isActive) {
                    shakeDetector?.register()
                } else {
                    shakeDetector?.unregister()
                }
            }
        }

        Log.d(TAG, "onCreate: PlaybackService created, session=${playbackManager.getMediaSession() != null}")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        val session = playbackManager.getMediaSession() as? MediaLibrarySession
        Log.d(TAG, "onGetSession: pkg=${controllerInfo.packageName} hasSession=${session != null}")
        return session
    }

    /**
     * Create a [MediaLibrarySession.Callback] that handles Android Auto browse requests.
     * Called by PlaybackManager when building the MediaLibrarySession.
     */
    fun createLibraryCallback(): MediaLibrarySession.Callback {
        return LibraryCallback()
    }

    private fun isTrustedController(controller: MediaSession.ControllerInfo): Boolean {
        return isTrustedController(
            appPackageName = packageName,
            controllerPackageName = controller.packageName,
            controllerUid = controller.uid,
        ) { pkg, uid ->
            packageManager.getPackagesForUid(uid)?.contains(pkg) == true
        }
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
        Log.d(TAG, "onDestroy: starting teardown")
        shakeDetector?.unregister()
        shakeDetector = null
        playbackManager.releaseAll()
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

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            Log.d(TAG, "▶ onConnect: pkg=${controller.packageName} uid=${controller.uid}")
            if (!isTrustedController(controller)) {
                Log.w(TAG, "onConnect: rejected untrusted controller pkg=${controller.packageName} uid=${controller.uid}")
                return MediaSession.ConnectionResult.reject()
            }

            // Explicitly grant all session + library browse commands so Android Auto
            // can discover and browse the media tree (the default super.onConnect()
            // does not include library-specific commands).
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) {
            Log.d(TAG, "◀ onDisconnected: pkg=${controller.packageName}")
            super.onDisconnected(session, controller)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d(TAG, "onGetLibraryRoot: pkg=${browser.packageName} params=$params")
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
            Log.d(TAG, "onGetChildren: parentId=$parentId page=$page pageSize=$pageSize pkg=${browser.packageName}")
            if (!isTrustedController(browser)) {
                Log.w(TAG, "onGetChildren: denied untrusted browser pkg=${browser.packageName} uid=${browser.uid}")
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            return serviceScope.future(Dispatchers.IO) {
                try {
                    val children = mediaBrowseTree.getChildren(parentId, page, pageSize)
                    Log.d(TAG, "onGetChildren: parentId=$parentId → ${children.size} items")
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
            Log.d(TAG, "onGetItem: mediaId=$mediaId pkg=${browser.packageName}")
            return serviceScope.future(Dispatchers.IO) {
                try {
                    val item = mediaBrowseTree.getItem(mediaId)
                    Log.d(TAG, "onGetItem: mediaId=$mediaId → found=${item != null}")
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
            Log.d(TAG, "onSearch: query='$query' pkg=${browser.packageName}")
            // Trigger async search; results delivered via onGetSearchResult
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val results = mediaBrowseTree.search(query)
                    Log.d(TAG, "onSearch: query='$query' → ${results.size} results")
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
            Log.d(TAG, "onGetSearchResult: query='$query' page=$page pkg=${browser.packageName}")
            return serviceScope.future(Dispatchers.IO) {
                try {
                    val results = mediaBrowseTree.search(query)
                        .drop(page * pageSize)
                        .take(pageSize)
                    Log.d(TAG, "onGetSearchResult: query='$query' → ${results.size} results")
                    LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
                } catch (e: Exception) {
                    Log.e(TAG, "onGetSearchResult($query) failed: ${e.message}", e)
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
                }
            }
        }

        /**
         * Called when Android Auto (or any controller) requests to play a media item.
         * We intercept the request, extract the book ID, load it via PlaybackManager,
         * and return the player's actual media items so Android Auto can display them.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val firstItem = mediaItems.firstOrNull()
            val mediaId = firstItem?.mediaId
            Log.d(TAG, "onSetMediaItems: mediaId=$mediaId count=${mediaItems.size} startIdx=$startIndex pkg=${controller.packageName}")

            if (!isTrustedController(controller)) {
                Log.w(TAG, "onSetMediaItems: denied untrusted controller pkg=${controller.packageName} uid=${controller.uid}")
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }

            if (mediaId != null) {
                val bookId = mediaBrowseTree.extractBookId(mediaId)
                if (bookId != null) {
                    Log.d(TAG, "onSetMediaItems: resolved bookId=$bookId, loading async…")
                    // Load the book asynchronously, then return the player's real media items
                    return serviceScope.future(Dispatchers.Main) {
                        try {
                            val success = playbackManager.loadBookByIdForAuto(bookId)
                            Log.d(TAG, "onSetMediaItems: loadBookById=$success")
                        } catch (e: Exception) {
                            Log.e(TAG, "onSetMediaItems: failed to load book $bookId", e)
                        }
                        // After loading, the player has the real tracks — return them
                        // so Android Auto knows what's playing
                        val player = playbackManager.getPlayer()
                        val itemCount = player?.mediaItemCount ?: 0
                        Log.d(TAG, "onSetMediaItems: returning $itemCount items to controller, pos=${player?.currentPosition}")
                        if (player != null && itemCount > 0) {
                            val items = (0 until itemCount).map {
                                player.getMediaItemAt(it)
                            }
                            MediaSession.MediaItemsWithStartPosition(
                                items, 0, player.currentPosition
                            )
                        } else {
                            Log.w(TAG, "onSetMediaItems: player empty after load, falling back to original items")
                            MediaSession.MediaItemsWithStartPosition(
                                mediaItems, startIndex, startPositionMs
                            )
                        }
                    }
                } else {
                    Log.d(TAG, "onSetMediaItems: mediaId=$mediaId is not a book, passing through")
                }
            }

            // Fallback: let the default handler deal with it
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }
    }
}
