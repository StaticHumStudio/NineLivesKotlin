package com.ninelivesaudio.app.service.local

/**
 * A local book reduced to the two things that survive a move: the name of the
 * folder it sits in, and the exact set of audio filenames inside it.
 *
 * A local book's id is a hash of its path under the picked root, so moving a
 * folder anywhere else in the tree produces a brand new id and the old row
 * reconciles away (issue #20). The user's place in the book would go with it.
 * This is the narrow, opt-out-when-unsure rescue for that: same folder name,
 * same filenames, one candidate on each side.
 */
data class LocalBookFingerprint(
    val id: String,
    val folderName: String,
    val trackFilenames: Set<String>,
)

/**
 * Pair books that vanished in this scan with books that arrived in it, and
 * return `vanished id -> arrived id` for the pairs that are beyond doubt.
 *
 * A pair is beyond doubt only when its (folder name, filename set) key matches
 * exactly ONE book on each side. Two identical copies of a book moving at once,
 * or a folder name reused across a library, is ambiguous: guessing there would
 * hand someone else's position to the wrong book, which is worse than making
 * the user find their place again. Ambiguous keys are dropped, silently and
 * deliberately. A book with no tracks is never matched at all.
 */
fun matchMovedLocalBooks(
    vanished: List<LocalBookFingerprint>,
    arrived: List<LocalBookFingerprint>,
): Map<String, String> {
    if (vanished.isEmpty() || arrived.isEmpty()) return emptyMap()

    fun key(book: LocalBookFingerprint) = book.folderName to book.trackFilenames
    fun unambiguous(books: List<LocalBookFingerprint>) = books
        .filter { it.folderName.isNotBlank() && it.trackFilenames.isNotEmpty() }
        .groupBy(::key)
        .filterValues { it.size == 1 }
        .mapValues { (_, matches) -> matches.single() }

    val arrivedByKey = unambiguous(arrived)
    return unambiguous(vanished)
        .mapNotNull { (key, gone) -> arrivedByKey[key]?.let { gone.id to it.id } }
        .toMap()
}

/**
 * The folder a track URI sits in, as a display name.
 *
 * SAF document URIs percent-encode the whole document path into one segment
 * (`.../document/primary%3AAudiobooks%2FDune%2F01.mp3`), so the folder name is
 * the second-to-last path element once decoded. Returns an empty string when
 * there is no parent folder to name, which [matchMovedLocalBooks] refuses to
 * match on.
 */
fun folderNameOfTrackUri(uri: String?): String {
    if (uri.isNullOrBlank()) return ""
    val decoded = runCatching {
        java.net.URLDecoder.decode(uri.substringBefore('?'), "UTF-8")
    }.getOrElse { return "" }
    val segments = decoded.split('/', ':').filter { it.isNotBlank() }
    return if (segments.size >= 2) segments[segments.size - 2] else ""
}
