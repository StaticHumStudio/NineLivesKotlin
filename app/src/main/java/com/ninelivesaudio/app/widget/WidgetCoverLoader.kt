package com.ninelivesaudio.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.service.ArtworkCodec
import com.ninelivesaudio.app.service.BoundedInputStream
import com.ninelivesaudio.app.service.artworkVersionKey
import com.ninelivesaudio.app.service.autoArtworkRemoteUrl
import com.ninelivesaudio.app.service.savedCoverModifiedMs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Small square covers for widgets, cached on disk under `cacheDir/widget_covers`.
 * Reads the book's effective cover (a saved or picked cover first, then the
 * scanned or server cover), the same source the app's own screens use.
 */
@Singleton
class WidgetCoverLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val dir: File get() = File(context.cacheDir, "widget_covers").apply { mkdirs() }

    internal fun fileName(book: AudioBook, px: Int): String = widgetCoverFileName(
        artworkVersionKey(book.id, book.coverPath, book.localCoverPath, savedCoverModifiedMs(book.localCoverPath)),
        px,
    )

    /** Disk only, never the network. Safe on the RemoteViews binder thread. */
    internal fun cached(book: AudioBook, px: Int): Bitmap? {
        val file = File(dir, fileName(book, px))
        if (!file.exists()) return null
        return runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        }.getOrNull()
    }

    /** Disk, else fetch and cache. Null when the book has no reachable cover. */
    internal suspend fun load(book: AudioBook, px: Int): Bitmap? {
        cached(book, px)?.let { return it }
        return withContext(Dispatchers.IO) {
            if (fetchToDisk(book, px)) cached(book, px) else null
        }
    }

    /** Fetches missing covers three at a time until [deadline], then prunes old ones. */
    internal suspend fun warm(books: List<AudioBook>, px: Int, deadline: Duration = 8.seconds) {
        withContext(Dispatchers.IO) {
            val missing = books.filter { !File(dir, fileName(it, px)).exists() }
            if (missing.isNotEmpty()) {
                val permits = Semaphore(3)
                withTimeoutOrNull(deadline) {
                    coroutineScope {
                        missing.map { book -> async { permits.withPermit { fetchToDisk(book, px) } } }.awaitAll()
                    }
                }
            }
            val keep = books.map { fileName(it, px) }.toSet()
            val files = dir.listFiles().orEmpty().map { it.name to it.lastModified() }
            coversToPrune(files, keep).forEach { File(dir, it).delete() }
        }
    }

    private fun fetchToDisk(book: AudioBook, px: Int): Boolean {
        val bytes = coverBytes(book, px) ?: return false
        val square = runCatching { squareRgb565(bytes, px) }.getOrNull() ?: return false
        val target = File(dir, fileName(book, px))
        val tmp = File(dir, target.name + ".tmp")
        return try {
            tmp.outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            tmp.renameTo(target)
        } catch (e: IOException) {
            Log.w(TAG, "cover write failed ${book.id}: ${e.message}")
            tmp.delete()
            false
        } finally {
            square.recycle()
        }
    }

    /** Center crops to a [px] square, so every sleeve carries exactly the budgeted bytes. */
    private fun squareRgb565(bytes: ByteArray, px: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val side = minOf(src.width, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, px, px, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        if (src !== scaled) src.recycle()
        return scaled
    }

    /** Mirrors the Android Auto artwork fetch: local file or content first, else the server thumbnail. */
    private fun coverBytes(book: AudioBook, px: Int): ByteArray? {
        val source = book.effectiveCoverPath ?: return null
        val uri = runCatching { Uri.parse(source) }.getOrNull() ?: return null
        val localOpener: (() -> InputStream)? = when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let(::File)?.takeIf { it.exists() }?.let { file -> { file.inputStream() } }
            "content" -> {
                { context.contentResolver.openInputStream(uri) ?: throw IOException("no stream for $uri") }
            }
            else -> null
        }
        val opener = localOpener ?: autoArtworkRemoteUrl(book.coverPath, px)?.let { url -> { openRemote(url) } }
            ?: return null
        return try {
            ArtworkCodec.decodeAndCompress(opener, px * 2, MAX_EMBED_BYTES, MIN_QUALITY)
        } catch (e: Exception) {
            Log.w(TAG, "cover read failed ${book.id}: ${e.message}")
            null
        }
    }

    private fun openRemote(url: String): InputStream {
        val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("http ${response.code}")
        }
        val body = response.body ?: run {
            response.close()
            throw IOException("empty body")
        }
        if (body.contentLength() > MAX_DOWNLOAD_BYTES) {
            response.close()
            throw IOException("cover too large")
        }
        return BoundedInputStream(body.byteStream(), MAX_DOWNLOAD_BYTES)
    }

    private companion object {
        const val TAG = "WidgetCoverLoader"
        const val MAX_DOWNLOAD_BYTES = 3L * 1024 * 1024
        const val MAX_EMBED_BYTES = 48 * 1024
        const val MIN_QUALITY = 50
    }
}
