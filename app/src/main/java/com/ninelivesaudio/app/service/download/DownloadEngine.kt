package com.ninelivesaudio.app.service.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.dao.DownloadItemDao
import com.ninelivesaudio.app.data.remote.ActiveRemoteScope
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.data.remote.AudiobookshelfApi
import com.ninelivesaudio.app.data.remote.StaleRemoteRequestException
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.service.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal enum class MetadataBoundaryOperation {
    COMPLETION,
    BACKFILL,
    DELETE,
}

/**
 * Streams an audiobook's audio files to disk: `.part`-then-atomic-rename,
 * skip-already-finished, per-file retry with exponential backoff, throttled
 * progress persistence, and cancellation cleanup.
 *
 * This is the side-effecting core of downloading, pulled out of DownloadManager
 * so it can be driven either by the legacy in-process scope or by a WorkManager
 * worker. The error-prone decisions live in [DownloadPolicies] and are unit
 * tested; the streaming orchestration here is verified by the on-device pass.
 */
@Singleton
class DownloadEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadItemDao: DownloadItemDao,
    private val audioBookDao: AudioBookDao,
    private val api: AudiobookshelfApi,
    private val apiService: ApiService,
    private val settingsManager: SettingsManager,
) {
    companion object {
        private const val TAG = "DownloadEngine"
        private const val BUFFER_SIZE = 81_920 // 80 KB
    }

    private val metadataPathMutex = Mutex()

    /** Timing receipt for the real metadata publication boundary. */
    @Volatile
    internal var metadataBoundaryObserver: (suspend (MetadataBoundaryOperation) -> Unit)? = null

    /**
     * Download every audio file of [audioBook] for [item]. Persists status and
     * progress to Room throughout and invokes [onProgress] for UI liveliness.
     * Returns the terminal [DownloadItem] (Completed / Failed / Paused); the
     * caller owns any completion/failure event emission.
     */
    internal suspend fun download(
        item: DownloadItem,
        audioBook: AudioBook,
        scope: ActiveRemoteScope,
        onProgress: suspend (downloadId: String, downloaded: Long, total: Long) -> Unit,
    ): DownloadItem {
        if (!canMutate(scope, item)) return item
        var download = item.copy(status = DownloadStatus.Downloading)
        if (!guardedUpsert(scope, download)) return item

        // Always ask for the expanded snapshot. A sparse catalog row is not an
        // authoritative replacement for durable offline metadata.
        val book = fetchFullBookDetails(scope, audioBook.id)?.let { detailedBook ->
            if (detailedBook.coverPath == null) detailedBook.copy(coverPath = audioBook.coverPath) else detailedBook
        } ?: audioBook

        // Create download directory
        if (!canMutate(scope, item)) return item
        val downloadDir = getDownloadPath(audioBook)
        if (!runScopedFilesystemMutation({ canMutate(scope, item) }) { downloadDir.mkdirs() }) return item

        if (book.audioFiles.isEmpty()) {
            download = download.copy(
                status = DownloadStatus.Failed,
                errorMessage = "No audio files available for download",
            )
            guardedUpsert(scope, download)
            return download
        }

        val totalBytes = estimateTotalBytes(book.audioFiles)
        download = download.copy(totalBytes = totalBytes)
        val resolvedFileNames = resolveDownloadFileNames(book.audioFiles)
        val canonicalAudioFiles = book.audioFiles.mapIndexed { index, audioFile ->
            audioFile.copy(localPath = File(downloadDir, resolvedFileNames[index]).absolutePath)
        }

        var downloadedBytes = 0L
        var lastPersistedBytes = 0L
        var lastPersistedAt = System.currentTimeMillis()
        val maxRetries = item.maxRetries

        // Download each audio file
        for (i in book.audioFiles.indices) {
            val audioFile = book.audioFiles[i]

            // Check for cancellation
            currentCoroutineContext().ensureActive()

            val fileName = resolvedFileNames[i]
            val finalPath = File(downloadDir, fileName)
            val partPath = File(downloadDir, "$fileName.part")
            if (!isContained(downloadDir, finalPath) || !isContained(downloadDir, partPath)) return item

            // Skip if already downloaded
            if (shouldSkipDownloadedFile(finalPath.exists(), finalPath.length())) {
                downloadedBytes += finalPath.length()
                onProgress(download.id, downloadedBytes, totalBytes)
                continue
            }

            // Retry loop for the CURRENT file. The old code used `continue`
            // in the catch block which advanced the for-loop index, skipping
            // the failed file instead of retrying it.
            var retryCount = 0
            var fileSuccess = false
            while (!fileSuccess) {
                // Snapshot byte count before this attempt — used to revert on retry
                val bytesBeforeAttempt = downloadedBytes

                try {
                    // Stream the file
                    if (audioFile.ino.isBlank()) {
                        throw Exception("Missing audio file identifier for $fileName")
                    }

                    val rawBookId = scope.decodeForEgress(book.id) ?: return item
                    val response = apiService.dispatchActiveRemoteScope(
                        scope,
                        request = { tag -> api.getAudioFileStream(rawBookId, audioFile.ino, tag) },
                        publish = { it },
                    ) ?: return item
                    if (!response.isSuccessful || response.body() == null) {
                        // Close the (error) body so the streaming connection is
                        // returned to the pool instead of leaking a socket/fd on
                        // every failed attempt (e.g. 401 after token expiry).
                        response.errorBody()?.close()
                        response.body()?.close()
                        throw Exception("HTTP ${response.code()}: Failed to download $fileName")
                    }

                    response.body()?.use { body ->
                        val streamed = withScopedPartOutput(
                            isCurrent = { canMutate(scope, download) },
                            partPath = partPath,
                        ) { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var bytesRead: Int

                                while (input.read(buffer).also { bytesRead = it } > 0) {
                                    currentCoroutineContext().ensureActive()
                                    if (!apiService.isCurrentActiveRemoteScope(scope)) {
                                        throw StaleRemoteRequestException()
                                    }
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead

                                    // Throttled progress updates (time + byte delta)
                                    val now = System.currentTimeMillis()
                                    val bytesDelta = downloadedBytes - lastPersistedBytes
                                    val timeDelta = now - lastPersistedAt

                                    if (shouldPersistProgress(bytesDelta, timeDelta)) {
                                        download = download.copy(downloadedBytes = downloadedBytes)
                                        if (!guardedUpsert(scope, download)) {
                                            return@withScopedPartOutput false
                                        }
                                        onProgress(download.id, downloadedBytes, totalBytes)
                                        lastPersistedBytes = downloadedBytes
                                        lastPersistedAt = now
                                    }
                                }
                            }
                            true
                        } ?: return item
                        if (!streamed) return item
                    }

                    // Atomic rename .part → final
                    if (finalPath.exists() && !runScopedFilesystemMutation(
                            { canMutate(scope, download) },
                        ) { finalPath.delete() }
                    ) return item
                    var renamed = false
                    if (!runScopedFilesystemMutation({ canMutate(scope, download) }) {
                            renamed = partPath.renameTo(finalPath)
                        }
                    ) return item
                    if (!renamed) {
                        throw Exception("Failed to finalize $fileName")
                    }
                    fileSuccess = true

                    // Always flush progress after each completed file.
                    download = download.copy(downloadedBytes = downloadedBytes)
                    if (!guardedUpsert(scope, download)) return item
                    onProgress(download.id, downloadedBytes, totalBytes)
                    lastPersistedBytes = downloadedBytes
                    lastPersistedAt = System.currentTimeMillis()

                } catch (e: CancellationException) {
                    // The drain worker was stopped (pause/cancel/system kill). Clean
                    // up the partial file and rethrow so cancellation propagates
                    // properly. The DownloadManager facade owns the resulting Room
                    // status; an interrupted item stays Downloading so the next drain
                    // worker resumes it (the engine skips already-finished files).
                    withContext(NonCancellable) {
                        deleteScopedPartIfCurrent(
                            isCurrent = { canMutate(scope, download) },
                            partPath = partPath,
                        )
                    }
                    throw e
                } catch (_: StaleRemoteRequestException) {
                    // The captured owner changed. The old task must not make a
                    // terminal write, delete its partial bytes, or retry under
                    // the new account.
                    return item
                } catch (e: Exception) {
                    if (canMutate(scope, download)) {
                        try { partPath.delete() } catch (cleanupError: Exception) {
                            // Ignore cleanup errors - file may already be deleted
                        }
                    }
                    downloadedBytes = bytesBeforeAttempt

                    retryCount++
                    if (retryCount < maxRetries) {
                        // Exponential backoff: 10s, 20s, 40s
                        delay(retryBackoffMs(retryCount))
                        // Loop will retry the same file
                    } else {
                        // Fail the entire download
                        download = download.copy(
                            status = DownloadStatus.Failed,
                            errorMessage = "$fileName: ${e.message}",
                            downloadedBytes = downloadedBytes,
                        )
                        guardedUpsert(scope, download)
                        return download
                    }
                }
            }
        }

        // All files downloaded successfully
        download = download.copy(
            status = DownloadStatus.Completed,
            downloadedBytes = maxOf(downloadedBytes, totalBytes),
            completedAt = System.currentTimeMillis(),
        )
        if (!guardedUpsert(scope, download)) return item

        // Fetch cover bytes outside the metadata boundary. The bytes and the
        // resulting cover path are committed with the canonical snapshot below.
        val coverBytes = fetchCoverBytes(scope, book)
        withMetadataPathBoundary(scope, download, MetadataBoundaryOperation.COMPLETION) {
            val currentItem = downloadItemDao.getRemoteByIdForOwner(download.id, scope.idPrefix)?.toDomain()
                ?: return@withMetadataPathBoundary
            if (currentItem.status != DownloadStatus.Completed || !canMutate(scope, currentItem)) {
                return@withMetadataPathBoundary
            }
            val bookEntity = audioBookDao.getById(audioBook.id) ?: return@withMetadataPathBoundary
            if (bookEntity.id != audioBook.id || scope.decodeForEgress(bookEntity.id) == null) {
                return@withMetadataPathBoundary
            }
            val currentBook = bookEntity.toDomain()
            val localCoverUri = if (coverBytes != null) {
                val coverFile = File(downloadDir, "cover.jpg")
                if (!isContained(downloadDir, coverFile)) return@withMetadataPathBoundary
                coverFile.writeBytes(coverBytes)
                Uri.fromFile(coverFile).toString()
            } else {
                currentBook.localCoverPath
            }
            audioBookDao.upsert(
                currentBook.copy(
                    isDownloaded = true,
                    localPath = downloadDir.absolutePath,
                    localCoverPath = localCoverUri,
                    audioFiles = canonicalAudioFiles,
                    chapters = book.chapters,
                ).toEntity()
            )
        }

        return download
    }

    /** Fetch cover bytes without performing a metadata-path filesystem mutation. */
    private suspend fun fetchCoverBytes(
        scope: ActiveRemoteScope,
        book: AudioBook,
    ): ByteArray? {
        if (book.coverPath.isNullOrEmpty()) return null
        return try {
            val rawBookId = scope.decodeForEgress(book.id) ?: return null
            val response = apiService.dispatchActiveRemoteScope(
                scope,
                request = { tag -> api.getCoverImage(rawBookId, dispatch = tag) },
                publish = { it },
            ) ?: return null
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                response.body()?.close()
                return null
            }
            val body = response.body() ?: return null
            val bytes = body.use { it.bytes() }
            bytes.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "fetchCoverBytes: cover fetch failed for ${book.id}: ${e.message}")
            null
        }
    }

    /** Fetch the canonical expanded snapshot through the scoped ApiService mapper. */
    internal suspend fun fetchFullBookDetails(scope: ActiveRemoteScope, audioBookId: String): AudioBook? {
        return try {
            apiService.getAudioBook(scope, audioBookId)
        } catch (_: Exception) {
            null
        }
    }

    /** Serialize completion, backfill publication, and deletion metadata work. */
    internal suspend fun <T> withMetadataPathBoundary(
        scope: ActiveRemoteScope,
        item: DownloadItem,
        operation: MetadataBoundaryOperation,
        block: suspend () -> T,
    ): T? = metadataPathMutex.withLock {
        if (!canMutate(scope, item)) {
            null
        } else {
            metadataBoundaryObserver?.invoke(operation)
            if (!canMutate(scope, item)) null else block()
        }
    }

    private suspend fun canMutate(scope: ActiveRemoteScope, item: DownloadItem): Boolean {
        if (scope.decodeForEgress(item.id) == null || scope.decodeForEgress(item.audioBookId) == null) return false
        return ownerScopedDownloadRowCurrent(
            isCurrent = { apiService.isCurrentActiveRemoteScope(scope) },
            readAudioBookId = {
                downloadItemDao.getRemoteByIdForOwner(item.id, scope.idPrefix)?.audioBookId
            },
            expectedAudioBookId = item.audioBookId,
        )
    }

    private suspend fun guardedUpsert(scope: ActiveRemoteScope, item: DownloadItem): Boolean {
        if (!canMutate(scope, item)) return false
        downloadItemDao.upsert(item.toEntity())
        return true
    }

    // ─── File Paths ──────────────────────────────────────────────────────────

    /** Get download directory for an audiobook. */
    private fun getDownloadPath(audioBook: AudioBook): File {
        val basePath = getBasePath()
        val folderName = downloadFolderName(audioBook.author, audioBook.title, audioBook.id)
        return File(basePath, folderName)
    }

    /** Base storage directory for all downloads. */
    private fun getBasePath(): File {
        // Respect user-configured path when possible, with path traversal validation.
        val configuredPath = settingsManager.currentSettings.downloadPath.trim()
        if (configuredPath.isNotEmpty()) {
            try {
                val candidate = File(configuredPath).canonicalFile
                // Reject paths targeting sensitive system directories
                val forbidden = listOf("/system", "/data/data", "/data/user", "/proc", "/dev")
                val isSafe = forbidden.none { candidate.absolutePath.startsWith(it) }
                if (isSafe) {
                    return candidate
                }
                Log.w(TAG, "getBasePath: Configured path rejected (targets system dir): $configuredPath")
            } catch (e: Exception) {
                Log.w(TAG, "getBasePath: Failed to resolve configured path: $configuredPath", e)
            }
        }

        // Fallback to app-specific external storage.
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(musicDir, "Audiobookshelf")
    }

    private fun isContained(parent: File, child: File): Boolean = try {
        child.canonicalFile.toPath().startsWith(parent.canonicalFile.toPath())
    } catch (_: Exception) {
        false
    }
}

/**
 * Write cover bytes to cover.jpg inside [dir] (creating it if needed) and return
 * the file. Pure file IO, so it is unit-testable without the Android framework.
 */
internal fun writeCoverFile(bytes: ByteArray, dir: File): File {
    dir.mkdirs()
    val file = File(dir, "cover.jpg")
    file.writeBytes(bytes)
    return file
}

/** Injectable boundary for every stale-sensitive filesystem mutation. */
internal suspend fun runScopedFilesystemMutation(
    isCurrent: suspend () -> Boolean,
    mutation: () -> Unit,
): Boolean {
    if (!isCurrent()) return false
    mutation()
    return true
}

/** Checks before opening a stream, since opening itself can create or truncate. */
internal suspend fun <T> withScopedPartOutput(
    isCurrent: suspend () -> Boolean,
    partPath: File,
    write: suspend (java.io.OutputStream) -> T,
): T? {
    if (!isCurrent()) return null
    val output = partPath.outputStream().buffered()
    return try {
        write(output)
    } finally {
        output.close()
    }
}

/** Cancellation cleanup must preserve old partial bytes after an owner switch. */
internal suspend fun deleteScopedPartIfCurrent(
    isCurrent: suspend () -> Boolean,
    partPath: File,
): Boolean = runScopedFilesystemMutation(isCurrent) { partPath.delete() }

/** The row read itself suspends, so currentness must be checked on both sides. */
internal suspend fun ownerScopedDownloadRowCurrent(
    isCurrent: suspend () -> Boolean,
    readAudioBookId: suspend () -> String?,
    expectedAudioBookId: String,
): Boolean {
    if (!isCurrent()) return false
    val actualAudioBookId = readAudioBookId()
    if (!isCurrent()) return false
    return actualAudioBookId == expectedAudioBookId
}
