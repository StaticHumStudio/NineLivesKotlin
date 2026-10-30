package com.ninelivesaudio.app.service.download

import android.util.Log
import com.ninelivesaudio.app.data.local.converter.encodeAudioFilesJson
import com.ninelivesaudio.app.data.local.converter.encodeChaptersJson
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.AudioFile
import com.ninelivesaudio.app.domain.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Downloads made by 2.1.5 and earlier hold an empty track list (#35). Their
// files play in file name order and they have no chapters. The server knows
// the real order, so the next time such a book plays online its list is taken,
// but only when it matches the files on disk exactly. Any doubt leaves the
// working download alone.

/** How long a play waits for the server's list before it plays without it. */
internal val MANIFEST_BACKFILL_TIMEOUT: Duration = 3.seconds

/** A server book that is downloaded but holds no track list. */
internal fun needsManifestBackfill(book: AudioBook): Boolean =
    !book.isLocal && book.isDownloaded && !book.localPath.isNullOrEmpty() && book.audioFiles.isEmpty()

internal sealed interface ManifestBackfill {
    data class Accept(val audioFiles: List<AudioFile>, val chapters: List<Chapter>) : ManifestBackfill
    data class Reject(val reason: String) : ManifestBackfill
}

/**
 * Whether the server's track list can stand in for the manifest of the files
 * in [folder]. Files on disk are the plain, non-empty files directly in the
 * folder, minus the cover, partial files and dot files.
 *
 * Rejected when the server lists no tracks, any duration is zero or below
 * (a zero would switch off resume seeking offline, which the name-order scan
 * does not), a resolved name would leave the folder, the counts differ (this
 * also catches a legacy folder shared by two editions), a name is missing on
 * disk, or a server size above zero differs from the file's length.
 */
internal fun decideManifestBackfill(
    folder: File,
    serverFiles: List<AudioFile>,
    serverChapters: List<Chapter>,
): ManifestBackfill {
    if (serverFiles.isEmpty()) return ManifestBackfill.Reject("the server lists no tracks")
    if (serverFiles.any { it.duration <= Duration.ZERO }) {
        return ManifestBackfill.Reject("the server lists a track with no duration")
    }
    val ordered = serverFiles.sortedBy { it.index }
    val names = resolveDownloadFileNames(ordered)
    val targets = names.map { name ->
        containedTrackFile(folder, name) ?: return ManifestBackfill.Reject("track name \"$name\" is not safe")
    }
    val onDisk = folder.listFiles()
        ?.filter { f ->
            f.isFile && f.length() > 0L && f.name != "cover.jpg" &&
                !f.name.endsWith(".part") && !f.name.startsWith(".")
        }
        .orEmpty()
    if (onDisk.size != ordered.size) {
        return ManifestBackfill.Reject("${onDisk.size} files on disk, ${ordered.size} tracks on the server")
    }
    val diskNames = onDisk.map { it.name }.toSet()
    ordered.indices.forEach { i ->
        if (names[i] !in diskNames) return ManifestBackfill.Reject("\"${names[i]}\" is not on disk")
        val size = ordered[i].size
        if (size > 0L && size != targets[i].length()) {
            return ManifestBackfill.Reject("\"${names[i]}\" is ${targets[i].length()} bytes, the server says $size")
        }
    }
    return ManifestBackfill.Accept(
        audioFiles = ordered.mapIndexed { i, file -> file.copy(localPath = targets[i].absolutePath) },
        chapters = serverChapters,
    )
}

/**
 * Gives a legacy download its track order and chapters (see the file note).
 * One refusal is remembered for the life of the process, so a book the server
 * disagrees with does not cost a fetch on every play.
 */
@Singleton
class DownloadManifestBackfill @Inject constructor(
    private val engine: DownloadEngine,
    private val audioBookDao: AudioBookDao,
) {
    private val refused: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The book with its manifest filled in and saved, or null when nothing
     * changed: not needed, refused before, the server could not be reached
     * (tried again next play), refused now, or the row moved on.
     */
    suspend fun backfill(book: AudioBook): AudioBook? = withContext(Dispatchers.IO) {
        if (!needsManifestBackfill(book)) return@withContext null
        val localPath = book.localPath ?: return@withContext null
        val key = book.id + "\u0000" + localPath
        if (key in refused) return@withContext null

        val folder = bookPathAsFile(localPath)?.takeIf { it.isDirectory }
        if (folder == null) {
            refuse(book, key, "the download folder is not a directory")
            return@withContext null
        }
        val server = engine.fetchFullBookDetails(book.id) ?: return@withContext null
        when (val decision = decideManifestBackfill(folder, server.audioFiles, server.chapters)) {
            is ManifestBackfill.Reject -> {
                refuse(book, key, decision.reason)
                null
            }
            is ManifestBackfill.Accept -> {
                val chapters = decision.chapters.ifEmpty { book.chapters }
                val updated = audioBookDao.backfillDownloadManifest(
                    id = book.id,
                    localPath = localPath,
                    audioFilesJson = encodeAudioFilesJson(decision.audioFiles),
                    chaptersJson = encodeChaptersJson(chapters),
                )
                if (updated == 0) {
                    null
                } else {
                    Log.i(TAG, "backfill accepted for ${book.id}: ${decision.audioFiles.size} tracks, ${chapters.size} chapters")
                    book.copy(audioFiles = decision.audioFiles, chapters = chapters)
                }
            }
        }
    }

    private fun refuse(book: AudioBook, key: String, reason: String) {
        refused += key
        Log.i(TAG, "backfill refused for ${book.id}: $reason")
    }

    private companion object {
        const val TAG = "ManifestBackfill"
    }
}
