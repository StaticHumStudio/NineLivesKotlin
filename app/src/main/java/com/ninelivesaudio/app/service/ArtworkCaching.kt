package com.ninelivesaudio.app.service

import android.content.ComponentCallbacks2
import com.ninelivesaudio.app.data.remote.coverVersionOf
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File
import java.net.URI

/**
 * The key Android Auto's browse art is cached and fetched under: the book,
 * the server cover's version (`ts`, see versionedCoverUrl), and for a saved
 * cover its path and modified time. An edited cover or a rewritten saved
 * cover gets a new key, so it is fetched again instead of the old art
 * sticking until the process dies (#70). A server cover is keyed by version,
 * not by address, so moving between a LAN and a VPN address refetches
 * nothing. A non-server cover (a local library's content:// art) is keyed by
 * its address.
 */
internal fun artworkVersionKey(
    bookId: String,
    coverPath: String?,
    localCoverPath: String?,
    localCoverModifiedMs: Long?,
): String {
    val cover = if (coverPath?.startsWith("http") == true) {
        coverVersionOf(coverPath)?.toString().orEmpty()
    } else {
        coverPath.orEmpty()
    }
    return listOf(bookId, cover, localCoverPath.orEmpty(), (localCoverModifiedMs ?: 0L).toString())
        .joinToString(separator = "\u0000")
}

/**
 * When the saved cover at [localCoverPath] was last written, for a
 * `file://` cover (a downloaded book's), else null. One stat, no read.
 */
internal fun savedCoverModifiedMs(localCoverPath: String?): Long? {
    if (localCoverPath == null || !localCoverPath.startsWith("file:")) return null
    val path = runCatching { URI(localCoverPath).path }.getOrNull() ?: return null
    return File(path).lastModified().takeIf { it > 0L }
}

/**
 * The last few now playing covers, keyed by book and cover version. Switching
 * from A to B and back to A embedded A's art straight away instead of
 * downloading it again, and an edited cover misses (#1, #70). Small on
 * purpose: a 512 pixel embed is up to 300 KB.
 */
internal class NowPlayingArtworkCache(maxEntries: Int = 4, maxTotalBytes: Long = 1_536L * 1024) {
    private val entries = BrowseArtworkCache(maxEntries, maxTotalBytes)

    fun get(bookId: String, coverPath: String?): ByteArray? = entries.get(artworkVersionKey(bookId, coverPath, null, null))

    fun put(bookId: String, coverPath: String?, bytes: ByteArray) {
        entries.put(artworkVersionKey(bookId, coverPath, null, null), bytes)
    }

    fun clear() = entries.clear()
}

/**
 * The "browse art landed" signal. One replayed slot, so a signal sent before
 * PlaybackService starts listening still arrives, and a burst coalesces into
 * the latest instead of failing to send (#1).
 */
internal fun artworkSignal(): MutableSharedFlow<Unit> =
    MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

/**
 * Whether a memory trim [level] should drop the art caches: real pressure
 * only. TRIM_MEMORY_UI_HIDDEN arrives whenever the phone screen turns off,
 * which in a car is most of the drive, and dropping art there would make
 * every Auto row download its cover again over cellular.
 */
@Suppress("DEPRECATION")
internal fun shouldDropArtworkOnTrim(level: Int): Boolean =
    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND

/**
 * Where the now playing cover is fetched from: the server cover at 512
 * pixels wide. Null for anything that is not http(s), so a local file:// or
 * content:// cover never becomes a network request (#1).
 */
internal fun nowPlayingRemoteArtworkUrl(coverPath: String?): String? = autoArtworkRemoteUrl(coverPath, 512)
