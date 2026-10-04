package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.DownloadStatus
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

// ─── Cancelled download cleanup (decision is unit-testable) ───────────────
//
// A cancelled download used to leave its partial folder on disk for good
// (#51). Cleaning it up deletes user files, so every rule here leans toward
// keeping them. Any doubt at all is a Keep.

/** Where a book's download folder sits and whether the app alone owns the root. */
internal data class DownloadLocation(
    val root: File,
    val folder: File,
    /**
     * True when the root is the app's own storage, which nothing else can
     * write to on API 30+. False for a user-configured download path, which
     * may hold the user's own audiobooks.
     */
    val rootIsAppOwned: Boolean,
)

/**
 * Pick the download root the same way the engine does, without creating it.
 *
 * A configured path wins when it resolves and stays out of system dirs.
 * Otherwise [defaultRoot] (the app's own storage). The root only counts as app
 * owned when it really is [defaultRoot], so a user who typed that same path in
 * by hand still gets the full cleanup. On fallback the [defaultRoot] instance
 * itself comes back, which the engine uses to log a rejected path.
 */
internal fun resolveDownloadRoot(configuredPath: String, defaultRoot: File): Pair<File, Boolean> {
    val trimmed = configuredPath.trim()
    if (trimmed.isNotEmpty()) {
        val candidate = runCatching { File(trimmed).canonicalFile }.getOrNull()
        if (candidate != null && FORBIDDEN_DOWNLOAD_ROOTS.none { candidate.absolutePath.startsWith(it) }) {
            val defaultCanonical = runCatching { defaultRoot.canonicalFile }.getOrNull()
            return candidate to (candidate == defaultCanonical)
        }
    }
    return defaultRoot to true
}

/** System dirs a configured download path may never point into. */
internal val FORBIDDEN_DOWNLOAD_ROOTS = listOf("/system", "/data/data", "/data/user", "/proc", "/dev")

/**
 * Whether the engine is provably off this download, so its folder can be
 * touched at all.
 *
 * A Downloading row only qualifies once the stop was confirmed (the stop gives
 * up after a timeout and the engine may still be streaming). Paused and Failed
 * rows are never picked by the drain. A Queued row can be picked at any
 * moment, and a Completed row is a finished download that cancel must leave
 * exactly as it was, so both keep their files.
 */
internal fun cancelMayTouchFiles(status: Int, wasDownloading: Boolean, stopConfirmed: Boolean): Boolean =
    when {
        wasDownloading -> stopConfirmed
        status == DownloadStatus.Paused.ordinal -> true
        status == DownloadStatus.Failed.ordinal -> true
        else -> false
    }

internal enum class CancelKeepReason {
    /**
     * The root is a user-picked download folder. Other tools can write there
     * (a `.part` file from a browser or a sync app looks just like ours), so
     * cancel never deletes anything from it.
     */
    USER_FOLDER,

    /** The book row is marked downloaded, so this is a finished download. */
    BOOK_DOWNLOADED,

    /** The folder is missing, so there is nothing to clean. */
    NO_FOLDER,

    /** The folder is not a plain direct child of the download root. */
    OUTSIDE_ROOT,

    /** Another book row or download points at this folder (#49). */
    SHARED,

    /** The folder holds something the engine never writes. */
    UNEXPECTED_CONTENTS,

    /** A path could not be resolved, so overlap cannot be ruled out. */
    UNRESOLVABLE,
}

internal sealed interface CancelCleanupDecision {
    data class Keep(val reason: CancelKeepReason) : CancelCleanupDecision

    /**
     * Delete exactly [files] (all directly inside the canonical [folder]), then
     * [folder] itself once it is empty.
     */
    data class Delete(
        val folder: File,
        val files: List<File>,
    ) : CancelCleanupDecision
}

/**
 * Decide what a cancelled download may delete from [location].
 *
 * Kept, in this order, when:
 * - the root is a user-picked download folder rather than the app's own storage,
 * - the book row says the book is downloaded,
 * - the folder is not a real direct child of the root (a symlink, the root
 *   itself, `..`, or anything that resolves elsewhere),
 * - the folder does not exist,
 * - any [otherBookPaths] entry (another book's stored localPath) is the
 *   folder, sits inside it, or contains it, or cannot be resolved,
 * - any [otherDownloadFolders] entry (where another download row would
 *   write) is the folder,
 * - the folder holds a subdirectory, a symlink, or anything not a plain file.
 *
 * Otherwise every file goes and then the empty folder. That only ever happens
 * in the app's own storage, which nothing else can write to.
 *
 * Content URIs in [otherBookPaths] are skipped. They come from the Storage
 * Access Framework, which cannot reach app storage on API 30+, and a
 * user-configured root is never touched at all.
 */
internal fun decideCancelCleanup(
    location: DownloadLocation,
    bookIsDownloaded: Boolean,
    otherBookPaths: List<String>,
    otherDownloadFolders: List<File>,
): CancelCleanupDecision {
    if (!location.rootIsAppOwned) return CancelCleanupDecision.Keep(CancelKeepReason.USER_FOLDER)
    if (bookIsDownloaded) return CancelCleanupDecision.Keep(CancelKeepReason.BOOK_DOWNLOADED)

    val root = canonicalOrNull(location.root)
        ?: return CancelCleanupDecision.Keep(CancelKeepReason.UNRESOLVABLE)
    val folderPath = location.folder.toPath()
    if (Files.isSymbolicLink(folderPath)) {
        return CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT)
    }
    val folder = canonicalOrNull(location.folder)
        ?: return CancelCleanupDecision.Keep(CancelKeepReason.UNRESOLVABLE)
    if (folder == root || folder.parentFile != root) {
        return CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT)
    }
    if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        return CancelCleanupDecision.Keep(CancelKeepReason.NO_FOLDER)
    }

    for (raw in otherBookPaths) {
        val other = bookPathAsFile(raw) ?: continue
        val otherCanonical = canonicalOrNull(other)
            ?: return CancelCleanupDecision.Keep(CancelKeepReason.UNRESOLVABLE)
        if (pathsOverlap(folder, otherCanonical)) {
            return CancelCleanupDecision.Keep(CancelKeepReason.SHARED)
        }
    }
    for (other in otherDownloadFolders) {
        val otherCanonical = canonicalOrNull(other)
            ?: return CancelCleanupDecision.Keep(CancelKeepReason.UNRESOLVABLE)
        if (otherCanonical == folder) return CancelCleanupDecision.Keep(CancelKeepReason.SHARED)
    }

    val children = try {
        // DirectoryStream, not Files.list(...).toList(): Stream.toList needs API 34.
        Files.newDirectoryStream(folder.toPath()).use { stream -> stream.toList() }
    } catch (_: IOException) {
        return CancelCleanupDecision.Keep(CancelKeepReason.UNRESOLVABLE)
    } catch (_: SecurityException) {
        return CancelCleanupDecision.Keep(CancelKeepReason.UNRESOLVABLE)
    }
    if (children.any { !Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) {
        return CancelCleanupDecision.Keep(CancelKeepReason.UNEXPECTED_CONTENTS)
    }

    return CancelCleanupDecision.Delete(folder, children.map(Path::toFile))
}

/**
 * The last look before a [CancelCleanupDecision.Delete] runs: true only when
 * no download row exists for [audioBookId], none would write to [folder], and
 * the engine is not on the book ([engineBookId]). [liveRowBookIds] and
 * [liveRowFolders] are read fresh right before the delete. A folder that will
 * not resolve counts as a match, so any doubt keeps the files.
 */
internal fun cancelCleanupStillClear(
    audioBookId: String,
    folder: File,
    liveRowBookIds: List<String>,
    liveRowFolders: List<File>,
    engineBookId: String?,
): Boolean {
    if (engineBookId == audioBookId) return false
    if (audioBookId in liveRowBookIds) return false
    val target = canonicalOrNull(folder) ?: return false
    return liveRowFolders.none { other -> canonicalOrNull(other)?.let { it == target } ?: true }
}

/**
 * Carry out a [CancelCleanupDecision.Delete]. Deletes each listed path without
 * following links, then the folder only if it is empty by then. Never recurses.
 * Returns how many files went.
 */
internal fun applyCancelCleanup(decision: CancelCleanupDecision.Delete): Int {
    val folder = decision.folder
    var deleted = 0
    for (file in decision.files) {
        val path = file.toPath()
        // Re-check right before the delete. Only a plain file directly inside
        // this exact folder is ever removed.
        if (file.parentFile != folder) continue
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
        if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) deleted++
    }
    // Fails on a non-empty folder, which is the point: anything that showed
    // up since the decision stays.
    runCatching { Files.deleteIfExists(folder.toPath()) }
    return deleted
}

private fun canonicalOrNull(file: File): File? = try {
    file.canonicalFile
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

/** A stored localPath as a File: plain paths and file:// URIs. Null for anything else. */
private fun bookPathAsFile(raw: String): File? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (value.startsWith("file:", ignoreCase = true)) {
        val path = runCatching { URI(value).path }.getOrNull()
        // A file URI that will not parse is still a file path of some kind.
        return File(path ?: value.removePrefix("file://"))
    }
    if (value.contains("://")) return null
    return File(value)
}

/** Equal, or one sits inside the other. Both must be canonical. */
private fun pathsOverlap(a: File, b: File): Boolean {
    val pa = a.toPath()
    val pb = b.toPath()
    return pa == pb || pa.startsWith(pb) || pb.startsWith(pa)
}
