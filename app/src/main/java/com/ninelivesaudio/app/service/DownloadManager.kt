package com.ninelivesaudio.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.service.download.CancelCleanupDecision
import com.ninelivesaudio.app.service.download.DOWNLOAD_WORK_NAME
import com.ninelivesaudio.app.service.download.applyCancelCleanup
import com.ninelivesaudio.app.service.download.cancelCleanupStillClear
import com.ninelivesaudio.app.service.download.cancelMayTouchFiles
import com.ninelivesaudio.app.service.download.decideCancelCleanup
import com.ninelivesaudio.app.service.download.DownloadEngine
import com.ninelivesaudio.app.service.download.DownloadNotifications
import com.ninelivesaudio.app.service.download.DownloadQueueWorker
import com.ninelivesaudio.app.service.download.estimateTotalBytes
import com.ninelivesaudio.app.service.download.finishInOwnerScope
import com.ninelivesaudio.app.service.download.lookUpInChunks
import com.ninelivesaudio.app.service.download.pauseKeepsCompletedRow
import com.ninelivesaudio.app.service.download.DownloadSlotStore
import com.ninelivesaudio.app.service.download.selectNextDownload
import com.ninelivesaudio.app.service.download.writeAfterEngineStops
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public facade for audiobook downloads: queue/pause/resume/cancel/delete and
 * progress/completion events.
 *
 * Execution is owned by a single [DownloadQueueWorker] that drains the Room
 * queue one book at a time as a dataSync foreground service, so downloads are
 * strictly sequential and survive app-switch / process death. This class
 * manages the Room queue, (re)enqueues the drain worker, and owns the event
 * flows the UI overlays on top of Room.
 */
@Singleton
class DownloadManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadItemDao: DownloadItemDao,
    private val audioBookDao: AudioBookDao,
    private val engine: DownloadEngine,
    private val slotStore: DownloadSlotStore,
    private val settingsManager: SettingsManager,
    private val connectivityMonitor: ConnectivityMonitor,
) {
    /**
     * Guards check-and-claim on the free tier's single offline slot.
     *
     * A slot lock, not a queue lock. Every path that claims, frees or reassigns
     * the slot takes it, and freeing plus recomputing plus persisting run as ONE
     * critical section. Check-then-insert without it lets two concurrent queue
     * attempts both see a free slot during the metadata fetch and both take it.
     *
     * Deliberately NOT held across the network round trip. The claim lands
     * first, the fetch runs outside the lock, and promotion re-takes it.
     */
    private val slotMutex = Mutex()

    /**
     * Keeps cancel cleanup from racing anything that brings a download back.
     *
     * Cancel holds it from the row read through the engine stop, the row delete,
     * the ownership snapshot and the file deletion. Resume holds it across its
     * read, its write and the drain start. Promotion in [queueDownload] holds it
     * for the write that makes a row pickable, and every drain start goes through
     * [enqueueDrain], which takes it too. Without it a resume could read a
     * Paused row, lose the race to cancel, write the row back and start the
     * engine, and cleanup would then delete files the resumed engine reuses.
     *
     * Lock order: this one first, [slotMutex] inside it, never the reverse.
     */
    private val rowMutex = Mutex()
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    /**
     * Where user pause, cancel and delete actually run. Their callers are screen
     * coroutines, and leaving the screen between the worker stop and the row
     * write used to leave the book Downloading with the queue stopped.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val WORKER_STOP_TIMEOUT_MS = 10_000L
        const val WORKER_STOP_POLL_MS = 100L
    }

    /**
     * Whether the whole download queue is user-paused, persisted across process
     * death.
     *
     * It used to be a @Volatile in-memory flag on the reasoning that a paused
     * queue is not downloading, so losing the flag was harmless. It was not. The
     * process is a prime kill candidate precisely BECAUSE it is idle, and the
     * paused notification carrying the only Resume control died with it. That
     * left a stopped queue, no notification, and no on-screen control to lift it.
     */
    private var downloadsPaused: Boolean
        get() = slotStore.downloadsPaused
        set(value) {
            slotStore.downloadsPaused = value
        }

    /** Whether the drain worker should hold (used by the worker to stop draining). */
    fun isDownloadsPaused(): Boolean = downloadsPaused

    /**
     * Whether a drain worker is actually inside doWork() in this process.
     *
     * Published by the worker rather than inferred from WorkInfo, because
     * WorkInfo cannot answer this question. WorkManager reports CANCELLED the
     * moment cancellation is RECORDED, not when the coroutine has unwound, so
     * `state.isFinished` returning true is entirely compatible with
     * DownloadEngine still writing rows a millisecond later.
     *
     * After process death this is false in a fresh process, which is correct:
     * no worker is running here.
     */
    @Volatile
    private var drainRunning = false

    /** Called by [DownloadQueueWorker] on entry to doWork(). */
    fun onDrainStarted() {
        drainRunning = true
    }

    /** Called by [DownloadQueueWorker] from a finally, on every exit path. */
    fun onDrainStopped() {
        drainRunning = false
    }

    /**
     * The book the engine is streaming right now in this process, or null.
     * Cancel cleanup checks it one last time before deleting anything.
     */
    @Volatile
    private var engineBookId: String? = null

    /** Called by [DownloadQueueWorker] right before it runs the engine on a book. */
    fun onEngineStarted(audioBookId: String) {
        engineBookId = audioBookId
    }

    /** Called by [DownloadQueueWorker] from a finally once the engine returns or throws. */
    fun onEngineStopped() {
        engineBookId = null
    }

    /**
     * Re-show the paused notification when a persisted pause survived the
     * process that owned its only Resume control.
     *
     * Without this a pause is a trap: the queue stays stopped, the notification
     * carrying Resume died with the process, and there is nothing left to press.
     */
    suspend fun restorePausedNotificationIfNeeded() {
        if (!downloadsPaused) return
        val pending = downloadItemDao.getDownloadable().map { it.toDomain() }
        if (pending.isEmpty()) return

        DownloadNotifications.showPaused(context, selectNextDownload(pending)?.title ?: "")
    }

    // ─── Progress Events ─────────────────────────────────────────────────────

    data class DownloadProgress(
        val downloadId: String,
        val progress: Double, // 0-100
        val downloadedBytes: Long,
        val totalBytes: Long,
    )

    private val _progressUpdates = MutableSharedFlow<DownloadProgress>(replay = 1, extraBufferCapacity = 64)
    val progressUpdates: SharedFlow<DownloadProgress> = _progressUpdates.asSharedFlow()

    private val _downloadCompleted = MutableSharedFlow<DownloadItem>(replay = 1, extraBufferCapacity = 8)
    val downloadCompleted: SharedFlow<DownloadItem> = _downloadCompleted.asSharedFlow()

    private val _downloadFailed = MutableSharedFlow<DownloadItem>(replay = 1, extraBufferCapacity = 8)
    val downloadFailed: SharedFlow<DownloadItem> = _downloadFailed.asSharedFlow()

    // ─── Queue Operations ────────────────────────────────────────────────────

    /**
     * What happened when a download was requested.
     *
     * Replaces a bare nullable return, which conflated "this is a local book",
     * "the free slot is taken" and "this book has no downloadable files" into
     * one indistinguishable null. Both callers discarded it, so the free-tier
     * cap was enforced completely silently: the user tapped Download, the claim
     * was refused, no row was written, and the button sat on an optimistic
     * "Queued..." forever with nothing to correct it.
     */
    sealed interface QueueResult {
        data class Queued(val item: DownloadItem) : QueueResult

        /**
         * The free tier already holds its one offline book.
         *
         * A refusal the user must be TOLD about. Silently declining is worse
         * than not offering the button at all.
         */
        data class BlockedByFreeSlot(val heldBy: String?) : QueueResult

        data class Failed(val item: DownloadItem) : QueueResult

        data class ServerUnavailable(val message: String) : QueueResult

        /** Local-folder books are not downloadable and never were. */
        data object NotApplicable : QueueResult
    }

    /**
     * Queue an audiobook for download.
     * Creates a DownloadItem, persists it Queued, and ensures the drain worker
     * is running so it gets picked up.
     *
     * Scanned-local books carry a synthetic id the server doesn't know, so they
     * are short-circuited here (returns null) rather than attempting a fetch
     * that would 404 against ABS.
     */
    suspend fun queueDownload(audioBook: AudioBook): QueueResult {
        if (audioBook.isLocal) {
            return QueueResult.NotApplicable
        }
        val remoteAccess = remoteMediaAccessDecision(
            book = audioBook.copy(isDownloaded = false, localPath = null),
            serverUrl = settingsManager.currentSettings.serverUrl,
            connectionStatus = connectivityMonitor.connectionStatus.value,
        )
        if (remoteAccess is RemoteMediaAccessDecision.Blocked) {
            return QueueResult.ServerUnavailable(remoteAccess.message)
        }
        val downloadId = UUID.randomUUID().toString()

        // Claim the slot BEFORE the network round trip. fetchFullBookDetails can
        // take seconds, and an unclaimed window that long is one a second queue
        // attempt walks straight through.
        var blockedBy: String? = null
        val claimed = slotMutex.withLock {
            if (!slotStore.canClaim(audioBook.id)) {
                blockedBy = slotStore.currentWinner()
                return@withLock false
            }

            downloadItemDao.upsert(
                DownloadItem(
                    id = downloadId,
                    audioBookId = audioBook.id,
                    title = audioBook.title,
                    status = DownloadStatus.Preparing,
                    startedAt = System.currentTimeMillis(),
                ).toEntity()
            )
            if (slotStore.slotApplies) slotStore.persistedWinner = audioBook.id
            true
        }
        if (!claimed) return QueueResult.BlockedByFreeSlot(blockedBy)

        // Everything from here to promotion runs with a claim held. An exception
        // or a cancelled coroutine in that window would otherwise strand a
        // Preparing row that the drain cannot see and that can never promote
        // itself, costing a free install its only slot until the next cold start.
        var claimSettled = false
        try {
            // Ensure we have file metadata before queuing.
            val resolvedBook = if (audioBook.audioFiles.isEmpty()) {
                engine.fetchFullBookDetails(audioBook.id) ?: audioBook
            } else {
                audioBook
            }

            val files = resolvedBook.audioFiles.mapNotNull { it.ino.takeIf { ino -> ino.isNotBlank() } }
            if (files.isEmpty()) {
                val failedItem = DownloadItem(
                    id = downloadId,
                    audioBookId = audioBook.id,
                    title = audioBook.title,
                    status = DownloadStatus.Failed,
                    errorMessage = "No downloadable audio files found for this book",
                    startedAt = System.currentTimeMillis(),
                )
                // Converts the claim rather than adding a second row, and frees the
                // slot in the same critical section. Failed never occupies the slot,
                // so a book with nothing downloadable must not cost the user theirs.
                slotMutex.withLock {
                    downloadItemDao.upsert(failedItem.toEntity())
                    if (slotStore.slotApplies) slotStore.resolveAndPersistWinner()
                }
                claimSettled = true
                _downloadFailed.tryEmit(failedItem)
                return QueueResult.Failed(failedItem)
            }

            val downloadItem = DownloadItem(
                id = downloadId,
                audioBookId = audioBook.id,
                title = audioBook.title,
                status = DownloadStatus.Queued,
                totalBytes = estimateTotalBytes(resolvedBook.audioFiles),
                downloadedBytes = 0,
                startedAt = System.currentTimeMillis(),
                filesToDownload = files,
            )

            // Compare-and-set off Preparing, never a blind upsert. The claim can
            // legitimately have gone during the fetch: the user cancelled, an
            // entitlement drop resolved a different winner, or a stranded-claim
            // sweep cleaned it up. A blind upsert would resurrect it and put the
            // free tier over its cap.
            val promoted = rowMutex.withLock {
                slotMutex.withLock {
                    val existing = downloadItemDao.getById(downloadId)
                    val rowStillOurs = existing?.status == DownloadStatus.Preparing.ordinal
                    // Status alone is not sufficient. An entitlement drop during the
                    // fetch can resolve a DIFFERENT winner while this row sits untouched
                    // in Preparing, and promoting it then would put a free install over
                    // its cap with the row looking perfectly legitimate.
                    val stillTheWinner = slotStore.canClaim(audioBook.id)

                    if (!rowStillOurs || !stillTheWinner) {
                        // Failed promotion deletes its own row rather than leaving an
                        // orphan occupying the slot forever.
                        if (existing != null) downloadItemDao.deleteById(downloadId)
                        return@withLock false
                    }
                    downloadItemDao.upsert(downloadItem.toEntity())
                    true
                }
            }
            claimSettled = true
            if (!promoted) return QueueResult.BlockedByFreeSlot(slotStore.persistedWinner)

            // Respect a paused queue: the book waits until the user resumes.
            if (!downloadsPaused) enqueueDrain(replace = false)

            return QueueResult.Queued(downloadItem)

        } finally {
            // NonCancellable is load-bearing. This whole function runs in the
            // caller's coroutine, and BookDetailViewModel's scope dies the moment
            // the user navigates away. Without it, releaseClaim suspends on the
            // slot mutex, immediately throws CancellationException, and the
            // Preparing row is never deleted... which silently consumes the free
            // tier's only download slot until the next cold start. A cleanup
            // path that is itself cancellable is not a cleanup path.
            if (!claimSettled) withContext(NonCancellable) { releaseClaim(downloadId) }
        }
    }

    /**
     * Drop a provisional claim that never became a download.
     *
     * Only deletes a row still sitting in Preparing, so it can never remove a
     * promoted download or a claim that has since been reused. Frees, recomputes
     * and persists in one critical section.
     */
    private suspend fun releaseClaim(downloadId: String) {
        slotMutex.withLock {
            val existing = downloadItemDao.getById(downloadId) ?: return@withLock
            if (existing.status != DownloadStatus.Preparing.ordinal) return@withLock

            downloadItemDao.deleteById(downloadId)
            if (slotStore.slotApplies) slotStore.resolveAndPersistWinner()
        }
    }

    /**
     * Everything the drain is allowed to pick up right now.
     *
     * While free, that is the persisted slot winner and nothing else.
     * `selectNextDownload` orders by age, so without this filter it would happily
     * meet a PRESERVED loser first and start downloading a book the free tier is
     * not entitled to keep.
     */
    suspend fun filterToSlotWinner(items: List<DownloadItem>): List<DownloadItem> {
        if (!slotStore.slotApplies) return items

        val winner = slotMutex.withLock { slotStore.currentWinner() }
        // Fail open. A null winner with live rows present should not happen,
        // since Queued and Downloading both occupy the slot. If it somehow does,
        // letting the drain proceed is better than wedging the queue silently.
        return if (winner == null) items else items.filter { it.audioBookId == winner }
    }

    /**
     * Re-resolve the slot after entitlement drops, and stand down the losers.
     *
     * Order matters and is the whole point. The winner is resolved first, the
     * running worker is cancelled, and the losing rows are only written AFTER
     * the worker is confirmed stopped. DownloadEngine writes progress and
     * terminal state unconditionally from a stale in-memory object and does not
     * take the slot mutex, so a losing row written while it is still running gets
     * clobbered straight back to Downloading.
     *
     * Losers are PAUSED, never deleted. Files stay, streaming keeps working, and
     * deleting down to one book stays the user's call.
     */
    suspend fun resolveSlotAfterEntitlementDrop() {
        if (!slotStore.slotApplies) return

        val winner = slotMutex.withLock { slotStore.resolveAndPersistWinner() }

        // Nothing downloading that should not be means nothing to stand down.
        // This early return is what makes the whole function safe to call at
        // cold start as well as on a transition: a free install emits its state
        // on every launch, and cancelling a healthy drain each time would be
        // worse than the problem being solved. It also means a downgrade that
        // happened while the app was dead is still handled, which a
        // transitions-only collector would miss.
        val hasLosingDownload = slotMutex.withLock {
            downloadItemDao.getAll().any {
                it.status == DownloadStatus.Downloading.ordinal && it.audioBookId != winner
            }
        }
        if (!hasLosingDownload) return

        stopDrainAndAwait()

        slotMutex.withLock {
            downloadItemDao.getAll()
                .filter {
                    it.status == DownloadStatus.Downloading.ordinal && it.audioBookId != winner
                }
                .forEach { downloadItemDao.upsert(it.copy(status = DownloadStatus.Paused.ordinal)) }
        }

        // The winner may now be downloadable and unblocked, so restart. Honours
        // the persisted pause: an automatic restart must never override a pause
        // the user set.
        if (!downloadsPaused) enqueueDrain(replace = false)
    }

    /**
     * Wait for the drain worker to actually stop.
     *
     * cancelUniqueWork returns as soon as the cancellation is recorded, not when
     * the worker has finished unwinding. Bounded, because a worker that will not
     * stop must not hang entitlement resolution forever: past the ceiling this
     * gives up and proceeds, which is the same risk profile as before this
     * function existed.
     */
    private suspend fun stopDrainAndAwait(): Boolean {
        workManager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
        return awaitDrainStopped()
    }

    /** True once the drain is confirmed stopped, false when the ceiling ran out first. */
    private suspend fun awaitDrainStopped(): Boolean {
        val deadline = System.currentTimeMillis() + WORKER_STOP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // The worker's own flag is the authority. WorkInfo is consulted only
            // as a second opinion for the case where the worker lives in another
            // process and can never clear our in-memory flag.
            if (!drainRunning) {
                val stillScheduled = runCatching {
                    workManager.getWorkInfosForUniqueWork(DOWNLOAD_WORK_NAME).await()
                }.getOrNull()?.any { !it.state.isFinished } ?: false

                if (!stillScheduled) return true
            }
            delay(WORKER_STOP_POLL_MS)
        }
        return false
    }

    /**
     * Delete provisional claims left behind by a process death mid-fetch.
     *
     * Runs before any queue, resume or drain path proceeds. A stranded Preparing
     * row is invisible to the drain, occupies the slot, and can never promote
     * itself, so without this sweep one badly timed kill costs the user their
     * only download slot permanently.
     */
    suspend fun cleanupStrandedClaims() {
        slotMutex.withLock {
            val stranded = downloadItemDao.getAll()
                .filter { it.status == DownloadStatus.Preparing.ordinal }

            if (stranded.isEmpty()) return@withLock

            stranded.forEach { downloadItemDao.deleteById(it.id) }
            if (slotStore.slotApplies) slotStore.resolveAndPersistWinner()
        }
    }

    /** Pause a download: mark it Paused; restart the drain if it was the active one. */
    suspend fun pauseDownload(downloadId: String): Unit = finishInOwnerScope(scope) {
        val entity = downloadItemDao.getById(downloadId) ?: return@finishInOwnerScope
        val wasDownloading = entity.status == DownloadStatus.Downloading.ordinal
        writeAfterEngineStops(wasDownloading, { stopDrainAndAwait() }) {
            // Re-read after the stop: the engine's last tick moved the bytes on,
            // and it may have finished the book in the meantime. A Completed row
            // only counts once the book itself is marked downloaded, since the
            // stop can land between those two writes.
            val latest = downloadItemDao.getById(downloadId) ?: return@writeAfterEngineStops
            val book = audioBookDao.getById(latest.audioBookId)
            if (pauseKeepsCompletedRow(latest.status, book?.isDownloaded, book?.localPath)) {
                return@writeAfterEngineStops
            }
            downloadItemDao.upsert(latest.copy(status = DownloadStatus.Paused.ordinal))
        }
        if (wasDownloading) {
            // The engine was stopped above; let the drain continue with the rest.
            enqueueDrain(replace = true)
        }
    }

    /** Resume a paused/failed download by re-queuing it and ensuring the drain runs. */
    suspend fun resumeDownload(downloadId: String) {
        // One critical section from the read to the drain start, so a cancel
        // either sees the Queued row or deletes it before this reads it.
        rowMutex.withLock {
            val entity = downloadItemDao.getById(downloadId) ?: return
            val item = entity.toDomain()
            if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

            // Reset to Queued. The engine skips already-finished files on re-run.
            downloadItemDao.upsert(entity.copy(status = DownloadStatus.Queued.ordinal))
            if (!downloadsPaused) enqueueDrainHoldingRowLock(replace = false)
        }
    }

    /** Pause the whole download queue: stop the drain and show a Resume notification. */
    suspend fun pauseQueue() {
        downloadsPaused = true
        // Stop the drain worker; the active book stays Downloading in Room and is
        // resumed first when the queue restarts.
        workManager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
        val current = selectNextDownload(downloadItemDao.getDownloadable().map { it.toDomain() })
        DownloadNotifications.showPaused(context, current?.title ?: "")
    }

    /** Resume the whole download queue. */
    suspend fun resumeQueue() {
        downloadsPaused = false
        DownloadNotifications.clearPaused(context)
        enqueueDrain(replace = false)
    }

    /** Cancel a download and clean up; restart the drain if it was the active one. */
    suspend fun cancelDownload(downloadId: String): Unit = finishInOwnerScope(scope) {
        // Held from the read to the last deleted file. Resume, promotion and
        // every drain start wait here, so nothing can bring this book's row
        // back or start the engine on it while its files are being judged.
        val wasDownloading = rowMutex.withLock {
            val entity = downloadItemDao.getById(downloadId)
            val downloading = entity?.status == DownloadStatus.Downloading.ordinal
            var stopConfirmed = false
            writeAfterEngineStops(downloading, { stopConfirmed = stopDrainAndAwait() }) {
                downloadItemDao.deleteById(downloadId)
                // Still before any drain restart below, so nothing is writing here.
                if (entity != null && cancelMayTouchFiles(entity.status, downloading, stopConfirmed)) {
                    removeCancelledPartialFiles(entity.audioBookId)
                }
            }
            downloading
        }

        if (downloadItemDao.getDownloadable().isEmpty()) {
            // Nothing left to download: stop the drain and clear the notification
            // so it doesn't linger after cancelling the last item.
            workManager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
            DownloadNotifications.clearAll(context)
        } else if (wasDownloading) {
            // More queued: the engine was stopped above, so continue with the rest.
            enqueueDrain(replace = true)
        }
    }

    /**
     * Delete the partial folder a cancelled download left behind (#51).
     *
     * Runs only once the engine is known to be off this book, and after this
     * book's row is gone, with [rowMutex] held by the caller. The rules live in
     * [decideCancelCleanup] and keep the files on any doubt, including when
     * another book or download points at the same folder (#49). Right before
     * deleting, [cancelCleanupStillClear] checks again that no row for the book
     * or folder came back and the engine is not on the book. Best effort: a
     * failure here never fails the cancel.
     */
    private suspend fun removeCancelledPartialFiles(audioBookId: String) {
        try {
            val bookEntity = audioBookDao.getById(audioBookId) ?: return
            val location = engine.downloadLocationFor(bookEntity.toDomain())
            val otherBookPaths = audioBookDao.getLocalPathsExcept(audioBookId)
            // Every download row still around, this book's included if it was
            // queued again, mapped to the folder it would write to.
            suspend fun liveRows() = downloadItemDao.getAll().map { it.audioBookId }.distinct()
            suspend fun foldersOf(bookIds: List<String>) =
                lookUpInChunks(bookIds) { audioBookDao.getByIds(it) }
                    .map { engine.downloadLocationFor(it.toDomain()).folder }
            val otherFolders = foldersOf(liveRows())
            val bookIsDownloaded = bookEntity.isDownloaded == 1 || !bookEntity.localPath.isNullOrEmpty()

            val decision = withContext(Dispatchers.IO) {
                decideCancelCleanup(location, bookIsDownloaded, otherBookPaths, otherFolders)
            }
            when (decision) {
                is CancelCleanupDecision.Keep ->
                    android.util.Log.d("DownloadManager", "cancel cleanup kept files: ${decision.reason}")
                is CancelCleanupDecision.Delete -> {
                    val rowBookIds = liveRows()
                    val clear = withContext(Dispatchers.IO) {
                        cancelCleanupStillClear(audioBookId, decision.folder, rowBookIds, foldersOf(rowBookIds), engineBookId)
                    }
                    if (!clear) {
                        android.util.Log.d("DownloadManager", "cancel cleanup kept files: a row or the engine came back")
                        return
                    }
                    val count = withContext(Dispatchers.IO) { applyCancelCleanup(decision) }
                    android.util.Log.d("DownloadManager", "cancel cleanup removed $count files")
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            android.util.Log.w("DownloadManager", "cancel cleanup skipped: ${e.message}")
        }
    }

    /** Delete a download's files and DB record. */
    suspend fun deleteDownload(audioBookId: String): Unit = finishInOwnerScope(scope) {
        val downloadEntity = downloadItemDao.getByAudioBookId(audioBookId)
        val wasDownloading = downloadEntity?.status == DownloadStatus.Downloading.ordinal
        writeAfterEngineStops(wasDownloading, { stopDrainAndAwait() }) {
            deleteDownloadedFilesAndRows(audioBookId, downloadEntity?.id)
        }
        if (wasDownloading) {
            // Restart the drain for whatever else is queued.
            enqueueDrain(replace = true)
        }
    }

    private suspend fun deleteDownloadedFilesAndRows(audioBookId: String, downloadId: String?) {
        // Read the book only now, after any engine on it has stopped, so a
        // completion that landed during the stop is the state being undone.
        // Use the actual localPath stored on the audiobook — this matches the path
        // set by the engine (basePath/Author - Title), not basePath/audioBookId.
        val bookEntity = audioBookDao.getById(audioBookId)

        withContext(Dispatchers.IO) {
            val localPath = bookEntity?.localPath
            if (!localPath.isNullOrEmpty()) {
                File(localPath).deleteRecursively()
            }
        }

        if (bookEntity != null) {
            // The cover.jpg lived inside localPath and was removed by deleteRecursively
            // above, so drop its reference too.
            audioBookDao.upsert(bookEntity.copy(isDownloaded = 0, localPath = null, localCoverPath = null))
        }
        if (downloadId != null) {
            downloadItemDao.deleteById(downloadId)
        }
    }

    /** Check if a book is downloaded. */
    suspend fun isBookDownloaded(audioBookId: String): Boolean {
        val entity = downloadItemDao.getByAudioBookId(audioBookId)
        return entity?.status == DownloadStatus.Completed.ordinal
    }

    // ─── Worker callbacks ──────────────────────────────────────────────────

    /** Republish live progress from the worker for the UI's liveliness overlay. */
    fun publishProgress(downloadId: String, downloaded: Long, total: Long) {
        val progress = if (total > 0) (downloaded.toDouble() / total * 100.0).coerceIn(0.0, 100.0) else 0.0
        _progressUpdates.tryEmit(
            DownloadProgress(
                downloadId = downloadId,
                progress = progress,
                downloadedBytes = downloaded,
                totalBytes = total,
            )
        )
    }

    /** Emit a terminal event for a finished download. */
    fun notifyTerminal(item: DownloadItem) {
        when (item.status) {
            DownloadStatus.Completed -> _downloadCompleted.tryEmit(item)
            DownloadStatus.Failed -> _downloadFailed.tryEmit(item)
            else -> { /* Paused/cancelled: no terminal event */ }
        }
    }

    // ─── Drain worker ────────────────────────────────────────────────────────

    /**
     * Ensure the single download-queue worker is running.
     *
     * [replace] = false (KEEP): used when adding work. If a drain worker is
     * already running it keeps going and picks up the new Queued row on its next
     * loop; otherwise a fresh one starts.
     *
     * [replace] = true (REPLACE): used when pausing/cancelling the active book.
     * It cancels the running drain (stopping the engine on the current book) and
     * starts a fresh drain that skips the now paused/removed item and continues.
     *
     * Takes [rowMutex], so no drain starts while a cancel is judging files.
     */
    private suspend fun enqueueDrain(replace: Boolean) {
        rowMutex.withLock { enqueueDrainHoldingRowLock(replace) }
    }

    /** [enqueueDrain] for a caller that already holds [rowMutex]. */
    private fun enqueueDrainHoldingRowLock(replace: Boolean) {
        // One gate for every automatic restart. pauseDownload, cancelDownload
        // and deleteDownload all restart the drain on their own, and none of
        // them knew about a queue-level pause, so any of them would silently
        // override it. resumeQueue clears the flag before calling here, so the
        // deliberate path is unaffected.
        if (downloadsPaused) return

        val request = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        android.util.Log.d("DownloadManager", "enqueueDrain policy=${if (replace) "REPLACE" else "KEEP"}")
        workManager.enqueueUniqueWork(DOWNLOAD_WORK_NAME, policy, request)
    }
}
