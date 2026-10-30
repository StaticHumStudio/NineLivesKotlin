package com.ninelivesaudio.app.service.download

import java.io.File
import java.net.URI
import java.net.URLDecoder

/** The Storage Access Framework provider for shared storage and SD cards. */
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** The Downloads provider. Its `raw:` document ids are absolute file paths. */
private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

/**
 * The file on disk a local folder import's track URI names, or null.
 *
 * Folder imports store each track as a SAF document URI. Deleting a server
 * download has to know which files those are, so it never takes audio an
 * import plays from the same folder (#88). Two providers name files on a
 * filesystem: shared storage (SD cards included) and the Downloads
 * provider's `raw:` documents. Any other provider gives null, since none of
 * them can point into a download root.
 *
 * The document id is the last `/document/` segment, decoded. For shared
 * storage it is `volume:path`: `primary` and SD card uuids resolve through
 * [volumeRoot], and `home` is the Documents folder of primary storage. For
 * the Downloads provider it is `raw:` and an absolute path. Null for an
 * unknown volume, any other Downloads id (a media store number), a malformed
 * URI, or a path with a `..` or `.` segment.
 *
 * Pure JVM, no `android.net.Uri`, so it is unit tested as is.
 */
internal fun safDocumentFile(uri: String, volumeRoot: (volumeId: String) -> File?): File? {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
    if (!parsed.scheme.equals("content", ignoreCase = true)) return null
    val authority = parsed.rawAuthority
    if (authority != EXTERNAL_STORAGE_AUTHORITY && authority != DOWNLOADS_AUTHORITY) return null
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
    val path = documentId.substring(colon + 1)
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.any { it == ".." || it == "." }) return null
    if (authority == DOWNLOADS_AUTHORITY) {
        if (volume != "raw" || !path.startsWith("/") || segments.isEmpty()) return null
        return segments.fold(File("/")) { dir, segment -> File(dir, segment) }
    }
    val base = when (volume) {
        "home" -> volumeRoot("primary")?.let { File(it, "Documents") }
        else -> volumeRoot(volume)
    } ?: return null
    return segments.fold(base) { dir, segment -> File(dir, segment) }
}
