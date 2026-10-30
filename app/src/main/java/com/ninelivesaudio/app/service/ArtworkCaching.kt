package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.remote.coverVersionOf
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
