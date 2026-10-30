package com.ninelivesaudio.app.service.download

import android.graphics.BitmapFactory
import android.util.Log
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.entity.DownloadedCoverRow
import com.ninelivesaudio.app.data.remote.coverVersionOf
import com.ninelivesaudio.app.service.BoundedInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.random.Random
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

/** Most one cover request may take, within what is left of the pass. */
private const val SAVED_COVER_FETCH_TIMEOUT_MS = 15_000L

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
 * One refresh pass: stale rows (see [savedCoverNeedsRefresh]), at most
 * [maxBooks] picked at random, fetched with [fetchCover] and written with
 * [replace]. A failed
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
    random: Random = Random.Default,
): Int {
    // A random pick of the stale rows, so a few covers that keep failing
    // can never hold every pass and starve the rest.
    val stale = rows
        .filter { savedCoverNeedsRefresh(it.coverPath, it.localCoverPath, savedModifiedMs(it)) }
        .shuffled(random)
        .take(maxBooks)
    var replaced = 0
    for (row in stale) {
        currentCoroutineContext().ensureActive()
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

/**
 * Whether bytes fetched for [snapshot] may still be written, decided again
 * under the ownership lock: the book is still downloaded ([current] not
 * null) with the same cover version, folder and saved cover, and its saved
 * cover is still stale. A book deleted and downloaded again while the fetch
 * ran has a fresh cover and keeps it.
 */
internal fun shouldWriteRefreshedCover(
    snapshot: DownloadedCoverRow,
    current: DownloadedCoverRow?,
    currentSavedModifiedMs: Long?,
): Boolean =
    current != null && current == snapshot &&
        savedCoverNeedsRefresh(current.coverPath, current.localCoverPath, currentSavedModifiedMs)

/**
 * The cover at [url], or null. Only http(s). The whole call is bounded by
 * [timeoutMs] and cancelling the caller cancels the request, so a slow
 * server never holds a sync open. A body over [SAVED_COVER_MAX_BYTES], one
 * that is not an image type, or one [isImage] cannot read (a sign in page
 * answered with 200) is refused, so a working offline cover is never
 * replaced with something that will not draw.
 */
internal suspend fun fetchSavedCoverBytes(
    client: OkHttpClient,
    url: String?,
    timeoutMs: Long,
    isImage: (ByteArray) -> Boolean,
): ByteArray? {
    if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null
    if (timeoutMs <= 0) return null
    val call = client.newCall(Request.Builder().url(url).build())
    call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
    val bytes = suspendCancellableCoroutine<ByteArray?> { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.use { readCoverBody(it) }
                } catch (e: IOException) {
                    null
                }
                continuation.resume(body)
            }
        })
    } ?: return null
    return bytes.takeIf(isImage)
}

private fun readCoverBody(response: Response): ByteArray? {
    if (!response.isSuccessful) return null
    val body = response.body ?: return null
    val type = body.contentType()
    if (type != null && type.type != "image") return null
    if (body.contentLength() > SAVED_COVER_MAX_BYTES) return null
    val bytes = BoundedInputStream(body.byteStream(), SAVED_COVER_MAX_BYTES + 1).use { it.readBytes() }
    return bytes.takeIf { it.isNotEmpty() && it.size <= SAVED_COVER_MAX_BYTES }
}

/** Whether [bytes] read as an image, decoding only the bounds. */
private fun decodesAsImage(bytes: ByteArray): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return options.outWidth > 0 && options.outHeight > 0
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
        val deadline = System.currentTimeMillis() + SAVED_COVER_REFRESH_TIMEOUT_MS
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
                        fetchCover = { bookId ->
                            val left = deadline - System.currentTimeMillis()
                            fetchSavedCoverBytes(
                                client = okHttpClient,
                                url = coverPaths[bookId],
                                timeoutMs = minOf(left, SAVED_COVER_FETCH_TIMEOUT_MS),
                                isImage = ::decodesAsImage,
                            )
                        },
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
     * the same saved cover and cover version, its cover is still stale, and
     * the engine is not on the book or its folder.
     */
    private suspend fun replaceIfStillDownloaded(row: DownloadedCoverRow, bytes: ByteArray, version: Long): Boolean {
        val target = savedCoverFileIn(row.localCoverPath, row.localPath) ?: return false
        val folder = target.parentFile ?: return false
        return ownership.whenEngineIsNotOn(row.id, folder) {
            val fresh = audioBookDao.getById(row.id)
            val current = fresh
                ?.takeIf { it.isLocal == 0 && it.isDownloaded == 1 && it.archivedAt == null }
                ?.let { DownloadedCoverRow(it.id, it.coverPath, it.localCoverPath, it.localPath) }
            val savedModified = target.takeIf { it.isFile }?.lastModified()?.takeIf { it > 0L }
            if (!shouldWriteRefreshedCover(row, current, savedModified)) return@whenEngineIsNotOn false
            replaceCoverFileAtomically(target, bytes, version)
        } ?: false
    }
}
