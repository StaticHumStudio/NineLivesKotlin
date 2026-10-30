package com.ninelivesaudio.app.data.remote

/**
 * [coverUrl] with the item's updated time ([updatedAtMs], epoch ms) as `ts`,
 * the way the Audiobookshelf web client asks for covers. An edited cover
 * keeps the same item address, so without a version every cache keyed by
 * the address (Coil on the phone, the Android Auto art cache) kept showing
 * the old picture (#70). No time, or a non-positive one, leaves the address
 * as it was.
 */
internal fun versionedCoverUrl(coverUrl: String, updatedAtMs: Long?): String {
    if (updatedAtMs == null || updatedAtMs <= 0) return coverUrl
    val separator = when {
        !coverUrl.contains('?') -> "?"
        coverUrl.endsWith("?") || coverUrl.endsWith("&") -> ""
        else -> "&"
    }
    return "$coverUrl${separator}ts=$updatedAtMs"
}

/** The `ts` version [versionedCoverUrl] put on [coverUrl], or null when it has none or a bad one. */
internal fun coverVersionOf(coverUrl: String?): Long? {
    val query = coverUrl?.substringAfter('?', "")?.substringBefore('#').orEmpty()
    if (query.isEmpty()) return null
    val value = query.split('&').firstOrNull { it.substringBefore('=') == "ts" && it.contains('=') }
        ?.substringAfter('=')
        ?: return null
    return value.toLongOrNull()?.takeIf { it > 0 }
}
