package com.ninelivesaudio.app.service.local

/**
 * A local book reduced to the things that survive a move: the name of the folder
 * it sits in, and the exact list of audio files inside it, names and sizes.
 *
 * A local book's id is a hash of its path under the picked root, so moving a
 * folder anywhere else in the tree produces a brand new id and the old row
 * reconciles away (issue #20). The user's place in the book would go with it.
 * This is the narrow, opt-out-when-unsure rescue for that: same folder name,
 * same files at the same sizes, one candidate on each side.
 */
data class LocalBookFingerprint(
    val id: String,
    val folderName: String,
    /**
     * Every track as name plus byte size, in a stable order. Sizes are what
     * stop a coincidence: a folder called "CD 1" holding "01.mp3" is a shape
     * half a library can share, and one book being deleted while an unrelated
     * one is added would otherwise read as a move and hand the wrong bookmark
     * over. A move copies bytes exactly, so requiring the sizes to line up
     * costs a real move nothing.
     *
     * A list, not a set: two same-named same-sized tracks across a merged
     * multi-disc book would collapse into one entry and make a fat book look
     * like a thin one.
     */
    val tracks: List<Pair<String, Long>>,
)

/**
 * Pair books that vanished in this scan with books that arrived in it, and
 * return `vanished id -> arrived id` for the pairs that are beyond doubt.
 *
 * A pair is beyond doubt only when its (folder name, track names and sizes)
 * key matches exactly ONE book on each side. Two identical copies of a book
 * moving at once is ambiguous: guessing there would hand someone else's
 * position to the wrong book, which is worse than making the user find their
 * place again. Ambiguous keys are dropped, silently and deliberately.
 *
 * A book with no tracks is never matched, and neither is one whose tracks all
 * report a size of zero. Some document providers do not report a size at all,
 * and a pile of zeroes is not evidence of anything: it would quietly drop the
 * match back to folder name and filenames, which is exactly the weak signal
 * the sizes are here to shore up.
 */
fun matchMovedLocalBooks(
    vanished: List<LocalBookFingerprint>,
    arrived: List<LocalBookFingerprint>,
): Map<String, String> {
    if (vanished.isEmpty() || arrived.isEmpty()) return emptyMap()

    fun key(book: LocalBookFingerprint) = book.folderName to book.tracks
    fun unambiguous(books: List<LocalBookFingerprint>) = books
        .filter {
            it.folderName.isNotBlank() &&
                it.tracks.isNotEmpty() &&
                it.tracks.any { (_, size) -> size > 0L }
        }
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
