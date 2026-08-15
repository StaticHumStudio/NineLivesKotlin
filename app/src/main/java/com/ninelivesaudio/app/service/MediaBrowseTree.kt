package com.ninelivesaudio.app.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.repository.AudioBookRepository
import com.ninelivesaudio.app.data.repository.LibraryRepository
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.isInActiveLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a browse tree for Android Auto / MediaLibraryService.
 *
 * Media ID scheme:
 *   "root"                → browse root (shown when the user opens the app in Auto)
 *   "recently_played"     → recently played books
 *   "library"             → all books in the active library or folder
 *   "downloaded"          → downloaded books in the active library or folder
 *   "book_{id}"           → a single playable book
 */
@Singleton
class MediaBrowseTree @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val audioBookRepository: AudioBookRepository,
    private val settingsManager: SettingsManager,
    private val apiService: ApiService,
    private val okHttpClient: OkHttpClient,
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        const val ROOT_ID = "root"
        const val RECENTLY_PLAYED_ID = "recently_played"
        const val LIBRARY_ID = "library"
        const val DOWNLOADED_ID = "downloaded"
        const val SETUP_REQUIRED_ID = "setup_required"
        private const val BOOK_PREFIX = "book_"
        private const val TAG = "MediaBrowseTree"

        // Browse-row thumbnails are smaller than the now-playing embed (512px in
        // PlaybackManager) — Auto renders these at list-row size, not full-screen.
        private const val ARTWORK_MAX_DOWNLOAD_BYTES = 3L * 1024 * 1024 // 3MB
        private const val ARTWORK_MAX_DIMENSION = 256
        private const val ARTWORK_MAX_EMBED_BYTES = 100 * 1024
        private const val ARTWORK_MIN_JPEG_QUALITY = 50
        private const val ARTWORK_MAX_CONCURRENT_FETCHES = 3

        internal fun rootItemIds(): List<String> =
            listOf(RECENTLY_PLAYED_ID, LIBRARY_ID, DOWNLOADED_ID)
    }

    // ─── Browse artwork cache ─────────────────────────────────────────────
    //
    // Android Auto/Automotive browse callbacks run while the user may be
    // driving, so a cache miss must never block one: getChildren() below
    // always returns immediately with whatever is cached (possibly no
    // artwork yet), and a miss kicks off a bounded-concurrency background
    // fetch that notifies the session once art lands so Auto re-renders.
    private val artworkCache = BrowseArtworkCache()
    private val artworkFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val artworkFetchSemaphore = Semaphore(ARTWORK_MAX_CONCURRENT_FETCHES)
    private val inFlightArtworkFetches = mutableSetOf<String>()
    private val inFlightArtworkFetchesLock = Any()

    // Bounds every book to at most one fetch ATTEMPT per browse epoch,
    // independent of inFlightArtworkFetches (which only tracks fetches
    // currently running). Without this, once the browse working set across
    // the three shared shelves exceeds the cache, eviction and the resulting
    // notify/re-query cycle feed each other forever: a miss fetches, the put
    // evicts an older entry, that evicted book's next re-query misses and
    // fetches again, evicting the first back out, and so on — permanent
    // cover traffic on cellular for any library bigger than the cache. It
    // also stops a permanently-failing cover (e.g. a 404) from re-downloading
    // on every single browse pass. Cleared on signals that the underlying
    // data changed (sync completed, source/library switched) and, because
    // neither of those fires in LOCAL mode, on a 5-minute age floor —
    // never on a plain re-query. See invalidateArtworkEpoch() and
    // ArtworkFetchEpoch's kdoc for why the floor is load-bearing.
    private val artworkFetchEpoch = ArtworkFetchEpoch()

    // Coalesces notifyChildrenChanged to once per browse burst instead of once
    // per book — see the comment in scheduleArtworkFetch() for why that matters.
    private val artworkFetchBurst = ArtworkFetchBurstTracker()

    private val _artworkUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits whenever a background artwork fetch lands. PlaybackService collects this
     *  and calls notifyChildrenChanged so Auto re-renders the browse lists with art. */
    val artworkUpdated: SharedFlow<Unit> = _artworkUpdated.asSharedFlow()

    // ─── Public API ────────────────────────────────────────────────────

    /** Top-level items shown when the user opens the app in Android Auto. */
    @OptIn(UnstableApi::class)
    fun getRootItems(canBrowse: Boolean = true): List<MediaItem> = autoRootItemIds(canBrowse).map { mediaId ->
        when (mediaId) {
            RECENTLY_PLAYED_ID -> buildBrowsableItem(mediaId, "Recently Played", "Continue listening")
            LIBRARY_ID -> buildBrowsableItem(mediaId, "Library", "Browse the active source")
            DOWNLOADED_ID -> buildBrowsableItem(mediaId, "Downloaded", "Available offline")
            SETUP_REQUIRED_ID -> buildBrowsableItem(
                mediaId,
                "Open Nine Lives on your phone",
                "Connect a server or choose Local Files",
            )
            else -> error("Unknown Android Auto root item: $mediaId")
        }
    }

    /** Resolve children for a given [parentId]. */
    suspend fun getChildren(parentId: String, page: Int = 0, pageSize: Int = 50): List<MediaItem> {
        apiService.awaitAuthReady()
        val settings = resolveActiveScope()
        val canBrowse = canBrowseAuto(settings, apiService.isAuthenticated)
        if (parentId == ROOT_ID) return getRootItems(canBrowse)
        if (!canBrowse) return emptyList()
        return when {
            parentId == RECENTLY_PLAYED_ID -> {
                recentBooksForAuto(
                    settings = settings,
                    maxItems = 20,
                    page = page,
                    pageSize = pageSize,
                    loadByLibrary = { libraryId, isLocal, limit ->
                        audioBookRepository.getRecentlyPlayedForAuto(libraryId, isLocal, limit)
                            .map { (book, _) -> book }
                    },
                )
                    .map(::bookToMediaItem)
            }

            parentId == DOWNLOADED_ID -> {
                val libraryId = settings.activeLibraryId ?: return emptyList()
                downloadedBooksForAuto(
                    audioBookRepository.getFilteredBooks(libraryId, downloadedOnly = true),
                    settings,
                )
                    .sortedBy { it.title.lowercase() }
                    .drop(page * pageSize)
                    .take(pageSize)
                    .map(::bookToMediaItem)
            }

            parentId == LIBRARY_ID -> {
                val libraryId = settings.activeLibraryId ?: return emptyList()
                browseBooksForAuto(
                    audioBookRepository.getFilteredBooks(libraryId),
                    settings,
                )
                    .sortedBy { it.title.lowercase() }
                    .drop(page * pageSize)
                    .take(pageSize)
                    .map(::bookToMediaItem)
            }

            else -> emptyList()
        }
    }

    /** Count children for Media3 invalidation without constructing browse items. */
    suspend fun getChildCount(parentId: String): Int {
        apiService.awaitAuthReady()
        val settings = resolveActiveScope()
        return autoBrowseChildCount(
            parentId = parentId,
            canBrowse = canBrowseAuto(settings, apiService.isAuthenticated),
            activeLibraryId = settings.activeLibraryId,
            activeIsLocal = settings.appMode == AppMode.LOCAL,
            countRecent = audioBookRepository::countRecentlyPlayedForAuto,
            countLibrary = audioBookRepository::countForAuto,
            countDownloaded = audioBookRepository::countDownloadedForAuto,
        )
    }

    /** Get a single item by its media ID. */
    suspend fun getItem(mediaId: String): MediaItem? {
        apiService.awaitAuthReady()
        val settings = resolveActiveScope()
        if (mediaId != ROOT_ID && mediaId != SETUP_REQUIRED_ID &&
            !canBrowseAuto(settings, apiService.isAuthenticated)
        ) return null
        return when {
            mediaId == ROOT_ID -> buildBrowsableItem(ROOT_ID, "Nine Lives Audio", null)
            mediaId == SETUP_REQUIRED_ID -> buildBrowsableItem(
                SETUP_REQUIRED_ID,
                "Open Nine Lives on your phone",
                "Connect a server or choose Local Files",
            )
            mediaId == RECENTLY_PLAYED_ID -> buildBrowsableItem(RECENTLY_PLAYED_ID, "Recently Played", "Continue listening")
            mediaId == LIBRARY_ID -> buildBrowsableItem(LIBRARY_ID, "Library", "Browse the active source")
            mediaId == DOWNLOADED_ID -> buildBrowsableItem(DOWNLOADED_ID, "Downloaded", "Available offline")

            mediaId.startsWith(BOOK_PREFIX) -> {
                val bookId = mediaId.removePrefix(BOOK_PREFIX)
                // An archived book has no source, so don't resolve it as a
                // playable Auto item (e.g. from a stale queued media id).
                audioBookRepository.getById(bookId)
                    ?.takeIf { browseBooksForAuto(listOf(it), settings).isNotEmpty() }
                    ?.let(::bookToMediaItem)
            }

            else -> null
        }
    }

    /** Search books by title/author. */
    suspend fun search(query: String): List<MediaItem> {
        apiService.awaitAuthReady()
        val settings = resolveActiveScope()
        if (!canBrowseAuto(settings, apiService.isAuthenticated)) return emptyList()
        return browseBooksForAuto(audioBookRepository.search(query), settings)
            .map(::bookToMediaItem)
    }

    /** Extract the original AudioBook ID from a media ID like "book_{id}". */
    fun extractBookId(mediaId: String): String? {
        return if (mediaId.startsWith(BOOK_PREFIX)) mediaId.removePrefix(BOOK_PREFIX) else null
    }

    /**
     * Starts a new browse-artwork epoch: every book becomes eligible for one
     * more fetch attempt. Call this only when the underlying data actually
     * changed — a library sync completing, or the source/library switching —
     * never from a plain browse re-query. PlaybackService wires this to the
     * same signals that already trigger a full parent refresh.
     */
    fun invalidateArtworkEpoch() {
        artworkFetchEpoch.clear()
    }

    private suspend fun resolveActiveScope(): AppSettings {
        val libraries = libraryRepository.getAll()
        val resolution = resolveActiveLibrarySelection(libraries, settingsManager.currentSettings)
        if (resolution.requiresPersistence) {
            settingsManager.updateSettings { latest ->
                resolveActiveLibrarySelection(libraries, latest).settings
            }
        }
        return settingsManager.currentSettings
    }

    // ─── Builders ──────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun bookToMediaItem(book: AudioBook): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.author)
            .setAlbumTitle(book.title)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)

        if (!book.narrator.isNullOrEmpty()) {
            metadataBuilder.setComposer(book.narrator)
        }

        if (book.genres.isNotEmpty()) {
            metadataBuilder.setGenre(book.genres.first())
        }

        // Never set artworkUri to the ABS server URL (or a local file://
        // content:// cover, which is equally unreachable out-of-process under
        // scoped storage). Android Auto's MediaDataLoader rejects cleartext
        // http outright and can't carry the Authorization header a
        // token-protected server needs even over https, so that URI is
        // deterministically dead (issue #89) — that's why browse rows never
        // showed a thumbnail. Embed a downscaled JPEG instead: serve it from
        // cache if we already have it, otherwise kick a bounded background
        // fetch and let this row render text-only until it lands.
        try {
            if (!book.effectiveCoverPath.isNullOrEmpty()) {
                val cached = artworkCache.get(book.id)
                if (cached != null) {
                    metadataBuilder.setArtworkData(cached, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                } else {
                    scheduleArtworkFetch(book)
                }
            }
        } catch (e: Exception) {
            // Art is never allowed to fail a browse row — this callback can run
            // while the user is driving.
            Log.w(TAG, "bookToMediaItem: artwork lookup failed bookId=${book.id}: ${e.message}")
        }

        return MediaItem.Builder()
            .setMediaId("$BOOK_PREFIX${book.id}")
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    /**
     * At-most-once-per-epoch, bounded-concurrency background fetch of
     * [book]'s cover. Caches the result. Does NOT signal [artworkUpdated]
     * per-book — [artworkFetchBurst] coalesces a whole browse burst into a
     * single notify, because notifyChildrenChanged is expensive: it makes
     * Android Auto re-query every row for this parent, and overlapping
     * re-queries can complete out of order — an older (less-cached) response
     * landing after a newer one visibly un-renders art that was already
     * showing. Firing one notify per book landing was exactly that: a burst
     * of ~9 covers produced ~9 overlapping re-queries and the resulting
     * flicker. Never throws — a fetch failure just leaves the row without
     * art.
     */
    private fun scheduleArtworkFetch(book: AudioBook) {
        val bookId = book.id
        // The epoch gate is checked and set synchronously, BEFORE ever
        // launching a coroutine, so a book that already had an attempt this
        // epoch never even reaches the scheduling machinery below — this is
        // what actually bounds the eviction/notify/re-query loop.
        if (!artworkFetchEpoch.attempt(bookId)) return
        artworkFetchScope.launch {
            // Both of these must be the FIRST things this coroutine body
            // does, so they either both happen or neither does. If the scope
            // is cancelled or the dispatcher rejects the task before this
            // body ever runs, nothing here executes at all — so nothing
            // leaks. Doing either of these OUTSIDE launch (the previous
            // shape) meant a body that never ran left its bookkeeping
            // permanently stuck: the bookId stayed marked in-flight forever,
            // and burst tracking never drained, wedging every future browse
            // notify for the life of the process.
            val addedToInFlight = synchronized(inFlightArtworkFetchesLock) {
                inFlightArtworkFetches.add(bookId)
            }
            if (!addedToInFlight) {
                // This book is already being fetched, so this scheduling is
                // redundant. Hand the epoch attempt back rather than letting
                // it be silently consumed here — see ArtworkFetchEpoch.release.
                artworkFetchEpoch.release(bookId)
                return@launch
            }
            artworkFetchBurst.begin()
            var landed = false
            try {
                artworkFetchSemaphore.withPermit {
                    val bytes = try {
                        fetchArtworkBytes(book)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (e: Exception) {
                        Log.w(TAG, "scheduleArtworkFetch: failed bookId=$bookId: ${e.message}")
                        null
                    }
                    if (bytes != null) {
                        artworkCache.put(bookId, bytes)
                        landed = true
                    }
                }
            } finally {
                synchronized(inFlightArtworkFetchesLock) { inFlightArtworkFetches.remove(bookId) }
                // Only notifies once the whole current burst has drained (no
                // fetch still in flight) and something actually changed since
                // the last notify. If more misses get scheduled while
                // draining, the pending count simply doesn't reach zero yet,
                // so the batch naturally extends to cover them too.
                if (artworkFetchBurst.end(landed)) _artworkUpdated.tryEmit(Unit)
            }
        }
    }

    /**
     * Cover bytes for [book], downscaled to browse-thumbnail size. Reads a
     * local file:// (downloaded ABS cover) or content:// (scanned local
     * cover) source directly; falls back to the authenticated remote fetch
     * for a server cover. Returns null on any failure.
     */
    private fun fetchArtworkBytes(book: AudioBook): ByteArray? {
        val source = book.localCoverPath ?: book.coverPath ?: return null
        val uri = runCatching { Uri.parse(source) }.getOrNull() ?: return null
        // A factory, not an already-open stream: ArtworkCodec may need to
        // re-open on a rare bounds-mark overrun (see its kdoc).
        val localOpener: (() -> java.io.InputStream)? = when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let(::File)?.takeIf { it.exists() }?.let { file -> { file.inputStream() } }
            "content" -> {
                { context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("openInputStream returned null for $uri") }
            }
            else -> null
        }
        if (localOpener != null) {
            return try {
                ArtworkCodec.decodeAndCompress(
                    localOpener,
                    ARTWORK_MAX_DIMENSION,
                    ARTWORK_MAX_EMBED_BYTES,
                    ARTWORK_MIN_JPEG_QUALITY,
                )
            } catch (e: Exception) {
                Log.w(TAG, "fetchArtworkBytes: local read failed bookId=${book.id}: ${e.message}")
                null
            }
        }

        val remoteUrl = book.coverPath?.takeIf { it.startsWith("http") } ?: return null
        // A factory rather than a single execute(): ArtworkCodec may need to
        // re-issue the request on a rare bounds-mark overrun (see its kdoc).
        val remoteOpener = {
            val request = Request.Builder().url(remoteUrl).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw java.io.IOException("http $code")
            }
            val body = response.body
            if (body == null) {
                response.close()
                throw java.io.IOException("empty body")
            }
            val contentLength = body.contentLength()
            if (contentLength > ARTWORK_MAX_DOWNLOAD_BYTES) {
                response.close()
                throw java.io.IOException("content length $contentLength exceeds cap $ARTWORK_MAX_DOWNLOAD_BYTES")
            }
            BoundedInputStream(body.byteStream(), ARTWORK_MAX_DOWNLOAD_BYTES)
        }
        return try {
            ArtworkCodec.decodeAndCompress(
                remoteOpener,
                ARTWORK_MAX_DIMENSION,
                ARTWORK_MAX_EMBED_BYTES,
                ARTWORK_MIN_JPEG_QUALITY,
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetchArtworkBytes: remote download failed bookId=${book.id}: ${e.message}")
            null
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildBrowsableItem(
        mediaId: String,
        title: String,
        subtitle: String?,
    ): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setIsPlayable(false)
            .setIsBrowsable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

        if (subtitle != null) {
            metadataBuilder.setSubtitle(subtitle)
        }

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }
}

/**
 * Bounded in-memory LRU of downscaled browse-row cover thumbnails, keyed by
 * book id. Capped by both entry count and total bytes so a long browse
 * session (scrolling a big library) can't grow this unbounded — a
 * misbehaving server returning maximum-size images every time is the worst
 * case this guards against.
 *
 * All three shelves (Recently Played, Library, Downloaded) share this one
 * cache, and Android Auto's browse UI fetches more than just the shelf the
 * user is looking at. A tight entry cap (previously 24) meant Library alone
 * — easily 30-plus books in a real collection — could evict a Recently
 * Played row that had *just* rendered with art, producing a visible
 * pop-in/pop-out flicker (issue #89 follow-up). The byte cap is the
 * intended real bound now: at the ~100KB-per-thumbnail ceiling that's still
 * only ~80 entries, and real JPEGs at 256px compress well under that, so
 * [maxEntries] is a generous backstop against pathological map growth
 * rather than the binding constraint.
 */
internal class BrowseArtworkCache(
    private val maxEntries: Int = 300,
    private val maxTotalBytes: Long = 8L * 1024 * 1024,
) {
    private val lock = Any()

    // accessOrder=true: get() counts as a touch, so eviction drops the
    // least-recently-used entry rather than strictly the oldest-inserted one.
    private val entries = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean {
            // Handled explicitly in put() (total-byte cap needs more than a
            // single-entry check), so this always returns false.
            return false
        }
    }
    private var totalBytes = 0L

    fun get(bookId: String): ByteArray? = synchronized(lock) { entries[bookId] }

    fun put(bookId: String, bytes: ByteArray) {
        synchronized(lock) {
            entries.remove(bookId)?.let { totalBytes -= it.size }
            entries[bookId] = bytes
            totalBytes += bytes.size
            evictUntilWithinCaps()
        }
    }

    /** Test-only: current entry count, to assert the cap holds. */
    internal fun size(): Int = synchronized(lock) { entries.size }

    private fun evictUntilWithinCaps() {
        val iterator = entries.entries.iterator()
        while ((entries.size > maxEntries || totalBytes > maxTotalBytes) && iterator.hasNext()) {
            val eldest = iterator.next()
            totalBytes -= eldest.value.size
            iterator.remove()
        }
    }
}

/**
 * Coalesces a burst of concurrent/sequential artwork fetches into a single
 * "notify now" decision, instead of one per book landing. [begin] is called
 * synchronously when a fetch is scheduled; [end] is called when that fetch
 * finishes (success or failure) and returns true exactly once the whole
 * burst has drained (no fetch still pending) with at least one success
 * since the last true return. If more fetches are scheduled while the
 * current batch is draining, the pending count simply doesn't reach zero
 * yet, so the batch naturally extends to cover them.
 */
internal class ArtworkFetchBurstTracker {
    private val lock = Any()
    private var pending = 0
    private var landedSinceLastNotify = false

    fun begin() {
        synchronized(lock) { pending++ }
    }

    fun end(landed: Boolean): Boolean = synchronized(lock) {
        if (landed) landedSinceLastNotify = true
        pending--
        if (pending == 0 && landedSinceLastNotify) {
            landedSinceLastNotify = false
            true
        } else {
            false
        }
    }
}

/**
 * Tracks which books have already had an artwork fetch ATTEMPTED during the
 * current browse epoch, so each book is fetched (or its permanent failure —
 * e.g. a 404 — retried) at most once per epoch, no matter how many times it
 * gets evicted from [BrowseArtworkCache] and re-queried in the meantime.
 *
 * Without this, once the browse working set (summed across the three shared
 * shelves) exceeds the cache, eviction and the notify-driven re-query it
 * triggers feed each other forever: book A misses and fetches, its put()
 * evicts book B, the resulting notify makes Auto re-query and B misses,
 * B's fetch evicts A back out, and so on — permanent cover-download traffic
 * for any library bigger than the cache, on top of the original flicker.
 *
 * [attempt] returns true (and marks the book) the first time it's called for
 * a given book id since the last epoch start; every later call for that id
 * within the same epoch returns false without marking anything new. [clear]
 * must only be called on a signal that the underlying data actually
 * changed — never on a plain re-query, which is exactly what this class
 * exists to bound.
 *
 * An epoch ALSO ages out on its own after [MAX_EPOCH_AGE_MS], and that is
 * load-bearing rather than belt-and-braces. The event-driven clears reach
 * MediaBrowseTree from exactly two places (PlaybackService's syncCompleted
 * collector and its source-change collector) and neither one fires in
 * AppMode.LOCAL: SyncManager.shouldRunSync gates on `!isLocalMode`, so
 * syncCompleted never emits there, and a local rescan changes no field the
 * source-change collector distinguishes on. Without the time floor, a local
 * cover whose read failed once — a transiently unavailable SAF provider, a
 * SecurityException after a permission hiccup — would stay text-only for the
 * entire process lifetime, which for a foreground media service can be days.
 * The same hole opens for an ABS user whose server is only reachable at home:
 * syncNow() bails on the reachability probe, so the epoch stops clearing for
 * the whole drive.
 *
 * [MAX_EPOCH_AGE_MS] deliberately mirrors SyncManager.DEFAULT_SYNC_INTERVAL_MS.
 * In ABS mode syncCompleted is already effectively a 5-minute heartbeat (it
 * emits on every sync pass that clears the pre-checks, whether or not
 * anything actually changed), and that is the configuration verified clean on
 * a real head unit. So the floor does not invent a new traffic profile, it
 * gives LOCAL and offline modes the one that was already proven.
 */
internal class ArtworkFetchEpoch(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val attempted = mutableSetOf<String>()
    private var epochStartedAtMs = nowMs()

    fun attempt(bookId: String): Boolean = synchronized(lock) {
        expireIfStaleLocked()
        attempted.add(bookId)
    }

    /**
     * Hands back an attempt that was claimed but never actually spent, so the
     * book stays eligible for the rest of the current epoch. Used when a
     * scheduled fetch turns out to be redundant because the same book is
     * already in flight: without this, an epoch clear landing mid-fetch lets
     * the re-query claim a fresh attempt that the in-flight duplicate then
     * discards, and if that original fetch fails the book waits a whole extra
     * epoch for a retry it already paid for.
     *
     * This cannot reopen the eviction loop: the in-flight fetch has already
     * consumed an attempt for this book, so releasing the duplicate's claim
     * restores exactly one unspent attempt, never an extra one.
     */
    fun release(bookId: String) {
        synchronized(lock) { attempted.remove(bookId) }
    }

    fun clear() {
        synchronized(lock) {
            attempted.clear()
            epochStartedAtMs = nowMs()
        }
    }

    private fun expireIfStaleLocked() {
        val now = nowMs()
        val age = now - epochStartedAtMs
        // A negative age means the wall clock moved backwards (manual change,
        // NTP correction). Treat that as expiry rather than trusting it —
        // otherwise a backwards jump locks every book out for the length of
        // the jump, which is the one direction this must never fail in.
        if (age < 0L || age >= MAX_EPOCH_AGE_MS) {
            attempted.clear()
            epochStartedAtMs = now
        }
    }

    internal companion object {
        const val MAX_EPOCH_AGE_MS = 300_000L // 5 minutes
    }
}

internal fun browseBooksForAuto(
    books: List<AudioBook>,
    settings: AppSettings,
): List<AudioBook> = books.filter { book ->
    !book.isArchived && book.isInActiveLibrary(settings)
}

internal fun downloadedBooksForAuto(
    books: List<AudioBook>,
    settings: AppSettings,
): List<AudioBook> = browseBooksForAuto(books, settings).filter { it.isDownloaded }

internal suspend fun recentBooksForAuto(
    settings: AppSettings,
    maxItems: Int,
    page: Int,
    pageSize: Int,
    loadByLibrary: suspend (libraryId: String, isLocal: Boolean, limit: Int) -> List<AudioBook>,
): List<AudioBook> {
    val activeLibraryId = settings.activeLibraryId ?: return emptyList()
    if (maxItems <= 0 || page < 0 || pageSize <= 0) return emptyList()
    val offset = page.toLong() * pageSize.toLong()
    if (offset >= maxItems) return emptyList()
    val loadLimit = minOf(maxItems.toLong(), offset + pageSize.toLong()).toInt()
    val isLocal = settings.appMode == AppMode.LOCAL
    return browseBooksForAuto(loadByLibrary(activeLibraryId, isLocal, loadLimit), settings)
        .take(maxItems)
        .drop(offset.toInt())
        .take(pageSize)
}

internal fun canBrowseAuto(settings: AppSettings, isAuthenticated: Boolean): Boolean =
    when (settings.appMode) {
        AppMode.AUDIOBOOKSHELF -> isAuthenticated && settings.selectedLibraryId != null
        AppMode.LOCAL -> settings.selectedLocalLibraryId != null
    }

internal fun autoBrowseParentsChangedAfterSync(): List<String> =
    listOf(
        MediaBrowseTree.RECENTLY_PLAYED_ID,
        MediaBrowseTree.LIBRARY_ID,
        MediaBrowseTree.DOWNLOADED_ID,
    )

internal fun autoBrowseParentsChangedAfterSourceChange(): List<String> =
    listOf(MediaBrowseTree.ROOT_ID) + autoBrowseParentsChangedAfterSync()

internal fun autoRootItemIds(canBrowse: Boolean): List<String> =
    if (canBrowse) MediaBrowseTree.rootItemIds() else listOf(MediaBrowseTree.SETUP_REQUIRED_ID)

internal suspend fun autoBrowseChildCount(
    parentId: String,
    canBrowse: Boolean,
    activeLibraryId: String?,
    activeIsLocal: Boolean,
    countRecent: suspend (String, Boolean) -> Int,
    countLibrary: suspend (String, Boolean) -> Int,
    countDownloaded: suspend (String, Boolean) -> Int,
): Int {
    if (parentId == MediaBrowseTree.ROOT_ID) return autoRootItemIds(canBrowse).size
    if (!canBrowse) return 0
    val libraryId = activeLibraryId ?: return 0
    return when (parentId) {
        MediaBrowseTree.RECENTLY_PLAYED_ID -> minOf(20, countRecent(libraryId, activeIsLocal))
        MediaBrowseTree.LIBRARY_ID -> countLibrary(libraryId, activeIsLocal)
        MediaBrowseTree.DOWNLOADED_ID -> countDownloaded(libraryId, activeIsLocal)
        else -> 0
    }
}
