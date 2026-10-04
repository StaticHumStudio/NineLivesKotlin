package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.data.local.entity.SlotBookRow
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import com.ninelivesaudio.app.entitlement.SlotCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free-tier slot check used to read and decode every book in every
 * library, at cold start and on every download claim. It now reads only the
 * downloaded books and the books named by download rows, and only four
 * columns of each. These pin that the candidates come out the same.
 */
class DownloadSlotCandidatesTest {

    @Test
    fun `only downloaded books and books with rows are read`() = runBlocking {
        val askedById = mutableListOf<List<String>>()
        var downloadedCalls = 0

        val books = loadSlotBooks(
            rowBookIds = listOf("queued", "done", "queued"),
            getDownloaded = {
                downloadedCalls++
                listOf(slot("done", downloaded = true), slot("kept-offline", downloaded = true))
            },
            getByIds = { ids ->
                askedById += ids
                ids.filter { it != "gone" }.map { slot(it) }
            },
        )

        assertEquals(1, downloadedCalls)
        // "done" already came back as downloaded, so only "queued" is looked up.
        assertEquals(listOf(listOf("queued")), askedById)
        assertEquals(setOf("done", "kept-offline", "queued"), books.keys)
    }

    @Test
    fun `row lookups are chunked under the bind limit`() = runBlocking {
        val chunks = mutableListOf<Int>()
        loadSlotBooks(
            rowBookIds = (0 until 1_201).map { "b$it" },
            getDownloaded = { emptyList() },
            getByIds = { ids ->
                chunks += ids.size
                emptyList()
            },
        )

        assertEquals(listOf(500, 500, 201), chunks)
    }

    @Test
    fun `no download rows means no id lookup`() = runBlocking {
        var byIdCalls = 0
        loadSlotBooks(
            rowBookIds = emptyList(),
            getDownloaded = { emptyList() },
            getByIds = {
                byIdCalls++
                emptyList()
            },
        )
        assertEquals(0, byIdCalls)
    }

    @Test
    fun `candidates match the former whole-library builder`() = runBlocking {
        // Every case the old builder distinguished: downloaded with and without
        // a row, a row whose book is gone, a local book, a stream-only book
        // with a row, duplicate rows, and a plain shelf book that must not show.
        val library = listOf(
            slot("downloaded-no-row", downloaded = true, path = "/b/1"),
            slot("downloaded-with-row", downloaded = true, path = "/b/2"),
            slot("local", local = true, downloaded = true, path = "/b/3"),
            slot("queued-not-downloaded"),
            slot("shelf-only"),
            slot("blank-path", downloaded = true, path = " "),
        )
        val rows = listOf(
            row("downloaded-with-row", DownloadStatus.Completed, startedAt = 10, completedAt = 20),
            row("queued-not-downloaded", DownloadStatus.Queued, startedAt = 30),
            row("book-gone", DownloadStatus.Paused, startedAt = 5),
            row("downloaded-with-row", DownloadStatus.Failed, startedAt = 40),
        )
        val rowsByBook = rows.groupBy { it.audioBookId }
        val filesExist: (String?) -> Boolean = { it == "/b/1" || it == "/b/3" }
        val progress: suspend (String) -> Long? = { id -> if (id == "local") 99L else null }

        val narrowed = loadSlotBooks(
            rowBookIds = rowsByBook.keys,
            getDownloaded = { library.filter { it.isDownloaded == 1 } },
            getByIds = { ids -> library.filter { it.id in ids } },
        )
        val actual = assembleSlotCandidates(rowsByBook, narrowed, filesExist, progress)
        val former = formerBuilder(library, rowsByBook, filesExist, progress)

        assertEquals(former.sortedBy { it.audioBookId }, actual.sortedBy { it.audioBookId })
        assertTrue(actual.none { it.audioBookId == "shelf-only" })
        assertEquals(DownloadStatus.Failed, actual.single { it.audioBookId == "downloaded-with-row" }.downloadStatus)
        assertEquals(false, actual.single { it.audioBookId == "book-gone" }.hasAudioBookRecord)
    }

    /** The pre-change builder, fed the whole library, kept here as the oracle. */
    private suspend fun formerBuilder(
        allBooks: List<SlotBookRow>,
        rowsByBook: Map<String, List<DownloadItem>>,
        filesExist: (String?) -> Boolean,
        progressMillis: suspend (String) -> Long?,
    ): List<SlotCandidate> {
        val offlineBooks = allBooks.filter { it.isDownloaded == 1 || rowsByBook.containsKey(it.id) }
        val bookIds = (offlineBooks.map { it.id } + rowsByBook.keys).distinct()
        return bookIds.map { bookId ->
            val book = offlineBooks.firstOrNull { it.id == bookId }
            val row = rowsByBook[bookId]?.maxByOrNull { it.startedAt ?: Long.MIN_VALUE }
            SlotCandidate(
                audioBookId = bookId,
                isLocal = book?.isLocal == 1,
                hasAudioBookRecord = book != null,
                downloadStatus = row?.status,
                isDownloaded = book?.isDownloaded == 1,
                hasLocalPath = !book?.localPath.isNullOrBlank(),
                filesExist = filesExist(book?.localPath),
                progressUpdatedAt = progressMillis(bookId),
                completedAt = row?.completedAt,
                startedAt = row?.startedAt,
            )
        }
    }

    private fun slot(id: String, local: Boolean = false, downloaded: Boolean = false, path: String? = null) =
        SlotBookRow(id = id, isLocal = if (local) 1 else 0, isDownloaded = if (downloaded) 1 else 0, localPath = path)

    private fun row(bookId: String, status: DownloadStatus, startedAt: Long? = null, completedAt: Long? = null) =
        DownloadItem(
            id = "row-$bookId-$startedAt",
            audioBookId = bookId,
            status = status,
            startedAt = startedAt,
            completedAt = completedAt,
        )
}
