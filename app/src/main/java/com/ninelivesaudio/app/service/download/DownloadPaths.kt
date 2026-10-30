package com.ninelivesaudio.app.service.download

import java.io.File
import java.net.URI
import java.net.URLDecoder

/** The Storage Access Framework provider for shared storage and SD cards. */
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * The file on disk a local folder import's track URI names, or null.
 *
 * Folder imports store each track as a SAF document URI. Deleting a server
 * download has to know which files those are, so it never takes audio an
 * import plays from the same folder (#88). Only the shared storage provider
 * names files on a filesystem, so any other provider gives null, and no
 * other provider can point into a download root anyway.
 *
 * The document id is the last `/document/` segment, decoded: `volume:path`.
 * `primary` and SD card uuids resolve through [volumeRoot]. `home` is the
 * Documents folder of primary storage. Null for an unknown volume, a
 * malformed URI, or a path with a `..` or `.` segment.
 *
 * Pure JVM, no `android.net.Uri`, so it is unit tested as is.
 */
internal fun safDocumentFile(uri: String, volumeRoot: (volumeId: String) -> File?): File? {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
    if (!parsed.scheme.equals("content", ignoreCase = true)) return null
    if (parsed.rawAuthority != EXTERNAL_STORAGE_AUTHORITY) return null
    val rawPath = parsed.rawPath ?: return null
    val marker = "/document/"
    val at = rawPath.lastIndexOf(marker)
    if (at < 0) return null
    val encodedId = rawPath.substring(at + marker.length)
    // One path segment. Its own slashes are encoded.
    if (encodedId.isEmpty() || encodedId.contains('/')) return null
    // URLDecoder reads "+" as a space. A document id never holds a bare "+".
    val documentId = runCatching { URLDecoder.decode(encodedId.replace("+", "%2B"), "UTF-8") }.getOrNull()
        ?: return null
    val colon = documentId.indexOf(':')
    if (colon <= 0) return null
    val volume = documentId.substring(0, colon)
    val segments = documentId.substring(colon + 1).split('/').filter { it.isNotEmpty() }
    if (segments.any { it == ".." || it == "." }) return null
    val base = when (volume) {
        "home" -> volumeRoot("primary")?.let { File(it, "Documents") }
        else -> volumeRoot(volume)
    } ?: return null
    return segments.fold(base) { dir, segment -> File(dir, segment) }
}
