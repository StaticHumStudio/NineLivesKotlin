package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.SyncMergeState
import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.AudioFile
import com.ninelivesaudio.app.service.download.completedDownloadBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A library sync reads book rows, merges the server copy over them, and saves
 * the result. A download that finishes, or a delete that lands, between that
 * read and that save must survive the save (#85).
 *
 * Each race is driven step by step on one thread: a hook inside the sync's
 * row read starts the other writer and lets it run as far as it can before
 * the sync carries on. [writer] stands in for SQLite's single writer, so a
 * write the sync holds back waits for it the way a Room write waits for a
 * transaction.
 */
class SyncDownloadRaceTest {

    private val rows = mutableMapOf<String, AudioBookEntity>()
    private val writer = Mutex()
    private var afterMergeRead: (suspend () -> Unit)? = null

    private val track = AudioFile(id = "t1", ino = "t1", index = 0, filename = "01.mp3")

    private suspend fun states(ids: List<String>): List<SyncMergeState> {
        val read = ids.mapNotNull { rows[it]?.toSyncMergeState() }
        afterMergeRead?.let { hook ->
            afterMergeRead = null
            hook()
        }
        return read
    }

    private suspend fun fullRows(ids: List<String>): List<AudioBookEntity> = ids.mapNotNull { rows[it] }

    private suspend fun merge(page: List<AudioBook>): List<AudioBook> =
        mergeSyncedBooksLean(page, ::states, ::fullRows) { emptyList() }

    private suspend fun save(books: List<AudioBook>) {
        books.forEach { rows[it.id] = it.toEntity() }
    }

    private val writeIfCurrent: suspend (suspend () -> Unit) -> Boolean = { write ->
        writer.withLock { write() }
        true
    }

    /** The full sync's page pass, saving [page] as one server page. */
    private suspend fun sync(page: List<AudioBook>) = runLibraryItemSyncPass(
        libraryId = "lib",
        fetchPages = { onPage ->
            onPage(page)
            RemoteResult.Ok(page.size)
        },
        mergeItems = ::merge,
        upsertAll = ::save,
        cachedNonDownloadedIds = { emptyList() },
        deleteByIds = { _, _ -> },
        deleteAllServerBooks = { },
        writeIfCurrent = writeIfCurrent,
    )

    /** The engine's completion commit, reading the row as it is when it writes. */
    private suspend fun completeDownload(id: String) = writer.withLock {
        rows[id] = completedDownloadBook(
            row = rows.getValue(id).toDomain(),
            downloaded = AudioBook(id = id, audioFiles = listOf(track)),
            localPath = "/d/$id",
            localCoverUri = null,
        ).toEntity()
    }

    /** A delete clearing the book's local copy. */
    private suspend fun deleteDownload(id: String) = writer.withLock {
        rows[id] = rows.getValue(id).copy(isDownloaded = 0, localPath = null, localCoverPath = null)
    }

    /** Run [other] once, right after the sync has read the rows it merges over. */
    private fun CoroutineScope.raceAfterMergeRead(other: suspend () -> Unit): () -> Job? {
        var job: Job? = null
        afterMergeRead = {
            job = launch { other() }
            yield()
        }
        return { job }
    }

    @Test
    fun `a sync that read the book before its download finished keeps the download`() = runBlocking {
        rows["A"] = AudioBook(id = "A", title = "Old title").toEntity()
        val raced = raceAfterMergeRead { completeDownload("A") }

        sync(listOf(AudioBook(id = "A", title = "Server title")))
        raced()?.join()

        val row = rows.getValue("A")
        assertEquals(1, row.isDownloaded)
        assertEquals("/d/A", row.localPath)
        assertEquals("Server title", row.title)
    }

    @Test
    fun `a sync that read the book before its download was deleted does not bring the local path back`() = runBlocking {
        rows["A"] = AudioBook(id = "A", title = "Old title", isDownloaded = true, localPath = "/d/A").toEntity()
        val raced = raceAfterMergeRead { deleteDownload("A") }

        sync(listOf(AudioBook(id = "A", title = "Server title")))
        raced()?.join()

        val row = rows.getValue("A")
        assertEquals(0, row.isDownloaded)
        assertNull(row.localPath)
    }

    @Test
    fun `the added-since save merges inside its transaction too`() = runBlocking {
        rows["A"] = AudioBook(id = "A", title = "Old title").toEntity()
        val raced = raceAfterMergeRead { completeDownload("A") }

        val saved = mergeAndSaveIfCurrent(
            page = listOf(AudioBook(id = "A", title = "Server title")),
            mergeItems = ::merge,
            upsertAll = ::save,
            writeIfCurrent = writeIfCurrent,
        )
        raced()?.join()

        assertEquals(listOf("A"), saved?.map { it.id })
        val row = rows.getValue("A")
        assertEquals(1, row.isDownloaded)
        assertEquals("/d/A", row.localPath)
        assertEquals("Server title", row.title)
    }

    @Test
    fun `a save for an account that signed out merges and writes nothing`() = runBlocking {
        rows["A"] = AudioBook(id = "A", title = "Old title").toEntity()
        var merged = false

        val saved = mergeAndSaveIfCurrent(
            page = listOf(AudioBook(id = "A", title = "Server title")),
            mergeItems = { merged = true; it },
            upsertAll = ::save,
            writeIfCurrent = { false },
        )

        assertNull(saved)
        assertEquals(false, merged)
        assertEquals("Old title", rows.getValue("A").title)
    }
}
