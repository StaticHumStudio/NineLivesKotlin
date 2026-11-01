package com.ninelivesaudio.app.service.local

import java.io.File
import java.net.URI

/**
 * Covers the user picked with Find a cover live in `files/user_covers/`, apart from the
 * scan's own art in `files/local_covers/`. Everything here is plain java.io and java.net
 * so it runs in JVM tests.
 */
internal const val USER_COVERS_DIR = "user_covers"

/** The same string `Uri.fromFile` makes for the app's own safe file paths. */
internal fun fileUriString(file: File): String = "file://" + file.absolutePath

/**
 * The durable "the user chose this cover" flag: a local book's `LocalCoverPath` names a
 * file directly inside [userCoversDir]. The folder scan never writes `LocalCoverPath` for
 * local books and the import keeps it, so the path itself is the flag and no extra Room
 * column (with its migration) is needed.
 */
internal fun isUserPickedCover(localCoverPath: String?, userCoversDir: File): Boolean {
    if (localCoverPath.isNullOrBlank()) return false
    return try {
        val uri = URI(localCoverPath)
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        val parent = File(path).absoluteFile.normalize().parentFile ?: return false
        parent == userCoversDir.absoluteFile.normalize()
    } catch (_: Exception) {
        false
    }
}

private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9_~.-]")

/** A new name per pick, so the URI changes and every image cache refreshes on its own. */
internal fun userCoverFileName(bookId: String, nowMs: Long): String =
    "${bookId.replace(UNSAFE_NAME_CHARS, "_")}-$nowMs.jpg"

/**
 * Writes the pick to a sibling temp file and renames it onto its final name, so a half
 * written file never becomes a cover. Null for empty bytes or any IO failure.
 */
internal fun writeUserCoverFile(bytes: ByteArray, userCoversDir: File, bookId: String, nowMs: Long): File? {
    if (bytes.isEmpty()) return null
    if (!userCoversDir.exists() && !userCoversDir.mkdirs()) return null
    val target = File(userCoversDir, userCoverFileName(bookId, nowMs))
    var temp: File? = null
    var committed = false
    return try {
        val t = File.createTempFile("pick-", ".tmp", userCoversDir)
        temp = t
        t.writeBytes(bytes)
        if (t.length() == 0L || !t.renameTo(target)) {
            null
        } else {
            committed = true
            target
        }
    } catch (_: Exception) {
        null
    } finally {
        if (!committed) temp?.delete()
    }
}

/** Copies a pick for a moved book under the new book's own name. Null when there is no pick. */
internal fun copyUserCoverFile(localCoverPath: String?, userCoversDir: File, toBookId: String, nowMs: Long): File? {
    if (!isUserPickedCover(localCoverPath, userCoversDir)) return null
    val source = runCatching { File(URI(localCoverPath!!).path) }.getOrNull() ?: return null
    if (!source.isFile) return null
    val bytes = runCatching { source.readBytes() }.getOrNull() ?: return null
    return writeUserCoverFile(bytes, userCoversDir, toBookId, nowMs)
}

/** Deletes a pick. Refuses (false) anything that is not a pick, such as the scan's own art. */
internal fun deleteUserCoverFile(localCoverPath: String?, userCoversDir: File): Boolean {
    if (!isUserPickedCover(localCoverPath, userCoversDir)) return false
    val file = runCatching { File(URI(localCoverPath!!).path) }.getOrNull() ?: return false
    return file.delete()
}

/** A set aside book takes its own pick with it, otherwise the scan cover kept for it. */
internal fun setAsideLocalCover(oldLocalCoverPath: String?, keptCover: String?, userCoversDir: File): String? =
    if (isUserPickedCover(oldLocalCoverPath, userCoversDir)) oldLocalCoverPath else keptCover

/** The vanished book's pick, when the book's new row has no pick of its own yet. */
internal fun coverToCarryOnMove(vanishedLocalCoverPath: String?, arrivedLocalCoverPath: String?, userCoversDir: File): String? =
    if (isUserPickedCover(vanishedLocalCoverPath, userCoversDir) && arrivedLocalCoverPath == null) {
        vanishedLocalCoverPath
    } else {
        null
    }
