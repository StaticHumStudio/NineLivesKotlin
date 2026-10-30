package com.ninelivesaudio.app.service.download

import android.util.Log
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.entity.DownloadedCoverRow
import com.ninelivesaudio.app.data.remote.coverVersionOf
import com.ninelivesaudio.app.service.BoundedInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

// ─── Saved cover refresh (#70) ──────────────────────────────────────────
//
// A download saves its cover as cover.jpg in its folder, so it shows with no
// network. An edit on the server never reached that file. Each server cover
// address now carries the item's updated time (versionedCoverUrl), and a
// sync replaces a saved cover that is older than that version.

/** Most saved covers one sync replaces, so a big edit on the server spreads over several syncs. */
internal const val SAVED_COVER_REFRESH_MAX_BOOKS = 20

/** Largest cover a refresh accepts. Anything bigger is refused, not cut. */
internal const val SAVED_COVER_MAX_BYTES = 8L * 1024 * 1024

/** File times can round to the second or two, so a cover this close to the version counts as current. */
internal const val SAVED_COVER_MTIME_SLACK_MS = 2_000L

/** The whole refresh pass gives up after this, so a slow server never holds a sync open. */
private const val SAVED_COVER_REFRESH_TIMEOUT_MS = 30_000L

private const val SAVED_COVER_FILE_NAME = "cover.jpg"

/**
 * Whether a downloaded book's saved cover is older than the server's: its
 * cover address has a version, it saved a cover, and that file is missing
 * ([savedModifiedMs] null) or was written before the version. A cover
 * address with no version (a row synced before this release) never
 * refreshes.
 */
internal fun savedCoverNeedsRefresh(coverPath: String?, localCoverPath: String?, savedModifiedMs: Long?): Boolean {
    val version = coverVersionOf(coverPath) ?: return false
    if (localCoverPath.isNullOrEmpty()) return false
    if (savedModifiedMs == null) return true
    return savedModifiedMs + SAVED_COVER_MTIME_SLACK_MS < version
}

/**
 * The saved cover file [localCoverPath] names, only when it is a `file:` URI
 * for a cover.jpg directly inside the download folder [localPath]. Anything
 * else (another folder, a sub folder, a SAF document, an audio file) is
 * null, so a refresh can never write outside the book's own cover.
 */
internal fun savedCoverFileIn(localCoverPath: String?, localPath: String?): File? {
    if (localCoverPath.isNullOrEmpty() || localPath.isNullOrEmpty()) return null
    val uri = runCatching { URI(localCoverPath) }.getOrNull() ?: return null
    if (!uri.scheme.equals("file", ignoreCase = true)) return null
    val path = uri.path?.takeIf { it.isNotEmpty() } ?: return null
    if (!localPath.startsWith("/")) return null
    val file = File(path).normalize()
    if (file.name != SAVED_COVER_FILE_NAME) return null
    val folder = File(localPath).normalize()
    return file.takeIf { it.parentFile == folder }
}

/**
 * Replace [target] with [bytes] in one step: write `cover.jpg.part` beside
 * it, then rename it over. Never creates a folder, so a download deleted
 * meanwhile stays deleted. Any failure removes the part file and keeps the
 * old cover. The new file's modified time is set to the server's
 * [versionMs] so the next check is exact (best effort, some file systems
 * refuse it).
 */
internal fun replaceCoverFileAtomically(target: File, bytes: ByteArray, versionMs: Long): Boolean {
    val folder = target.parentFile ?: return false
    if (!folder.isDirectory) return false
    val part = File(folder, target.name + ".part")
    return try {
        FileOutputStream(part).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!part.renameTo(target)) {
            part.delete()
            return false
        }
        target.setLastModified(versionMs)
        true
    } catch (e: IOException) {
        if (part.isFile) part.delete()
        false
    }
}

/**
 * One refresh pass: every stale row (see [savedCoverNeedsRefresh]), at most
 * [maxBooks], fetched with [fetchCover] and written with [replace]. A failed
 * fetch or write skips that book only and is reported to [onFailure]. Cancellation is never swallowed.
 * Returns how many covers were replaced.
 */
internal suspend fun refreshSavedCovers(
    rows: List<DownloadedCoverRow>,
    savedModifiedMs: (DownloadedCoverRow) -> Long?,
    fetchCover: suspend (bookId: String) -> ByteArray?,
    replace: suspend (row: DownloadedCoverRow, bytes: ByteArray, versionMs: Long) -> Boolean,
    maxBooks: Int = SAVED_COVER_REFRESH_MAX_BOOKS,
    onFailure: (bookId: String, reason: String) -> Unit = { _, _ -> },
): Int {
    val stale = rows
        .filter { savedCoverNeedsRefresh(it.coverPath, it.localCoverPath, savedModifiedMs(it)) }
        .take(maxBooks)
    var replaced = 0
    for (row in stale) {
        val version = coverVersionOf(row.coverPath) ?: continue
        val bytes = try {
            fetchCover(row.id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            onFailure(row.id, "fetch failed: ${e.message}")
            null
        }
        if (bytes == null || bytes.isEmpty()) continue
        val ok = try {
            replace(row, bytes, version)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            onFailure(row.id, "write failed: ${e.message}")
            false
        }
        if (ok) replaced++
    }
    return replaced
}

private const val TAG = "DownloadedCoverRefresh"

/**
 * Replaces downloaded books' saved covers that are older than the server's
 * cover version (#70). Runs after a sync's library pass. Fetches happen
 * outside the download ownership lock, and each write runs under it only
 * while the engine is not on that book, after reading the row again.
 */
@Singleton
class DownloadedCoverRefresher @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val audioBookDao: AudioBookDao,
    private val ownership: DownloadOwnership,
) {
    /** Replace stale saved covers. Gives up after 30 s. Never throws except cancellation. */
    suspend fun refreshStale(): Int {
        var replaced = 0
        try {
            withTimeoutOrNull(SAVED_COVER_REFRESH_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val rows = audioBookDao.getDownloadedCoverRows()
                        .filter { savedCoverFileIn(it.localCoverPath, it.localPath)?.parentFile?.isDirectory == true }
                    val coverPaths = rows.associate { it.id to it.coverPath }
                    refreshSavedCovers(
                        rows = rows,
                        savedModifiedMs = { row ->
                            savedCoverFileIn(row.localCoverPath, row.localPath)
                                ?.takeIf { it.isFile }
                                ?.lastModified()
                                ?.takeIf { it > 0L }
                        },
                        fetchCover = { bookId -> fetchCover(coverPaths[bookId]) },
                        replace = { row, bytes, version ->
                            replaceIfStillDownloaded(row, bytes, version).also { if (it) replaced++ }
                        },
                        onFailure = { bookId, reason -> Log.w(TAG, "refreshStale: bookId=$bookId $reason") },
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.w(TAG, "refreshStale: failed: ${e.message}")
        }
        return replaced
    }

    /**
     * Writes only while the book is still downloaded to the same folder with
     * the same saved cover, and the engine is not on it.
     */
    private suspend fun replaceIfStillDownloaded(row: DownloadedCoverRow, bytes: ByteArray, version: Long): Boolean =
        ownership.whenEngineIsNotOn(row.id) {
            val fresh = audioBookDao.getById(row.id) ?: return@whenEngineIsNotOn false
            if (fresh.isDownloaded != 1 || fresh.archivedAt != null) return@whenEngineIsNotOn false
            if (fresh.localPath != row.localPath || fresh.localCoverPath != row.localCoverPath) return@whenEngineIsNotOn false
            val target = savedCoverFileIn(fresh.localCoverPath, fresh.localPath) ?: return@whenEngineIsNotOn false
            replaceCoverFileAtomically(target, bytes, version)
        } ?: false

    /** The cover at the row's versioned server address, refused over [SAVED_COVER_MAX_BYTES]. */
    private fun fetchCover(coverPath: String?): ByteArray? {
        val url = coverPath?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) return null
            val body = it.body ?: return null
            if (body.contentLength() > SAVED_COVER_MAX_BYTES) return null
            val bytes = BoundedInputStream(body.byteStream(), SAVED_COVER_MAX_BYTES + 1).use { stream -> stream.readBytes() }
            return bytes.takeIf { b -> b.isNotEmpty() && b.size <= SAVED_COVER_MAX_BYTES }
        }
    }
}
