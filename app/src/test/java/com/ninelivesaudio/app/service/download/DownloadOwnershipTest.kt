package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.AudioFile
import com.ninelivesaudio.app.domain.model.DownloadItem
import com.ninelivesaudio.app.domain.model.DownloadStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Who owns a download folder changes only under one lock, and delete and
 * cancel judge and remove files under that same lock (#49). Each race here is
 * driven step by step: a hook inside one side's reads starts the other side
 * and lets it run as far as it can before the first side carries on. All of
 * it runs on one thread, so the interleaving is the same on every run.
 */
class DownloadOwnershipTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun track(name: String, index: Int = 0) = AudioFile(id = name, ino = name, index = index, filename = name)

    /**
     * Room and the path rules, in memory, with the engine's real folder rule.
     * The races here play out in shared "Author - Title" folders left by
     * downloads that started before each book got its own folder (#49).
     */
    private class FakeReads(val root: File) : FolderReads {
        val books = mutableMapOf<String, AudioBook>()
        val rows = mutableMapOf<String, DownloadItem>()
        var afterSharerIdsRead: (suspend () -> Unit)? = null

        override suspend fun book(id: String) = books[id]
        override suspend fun books(ids: List<String>) = ids.mapNotNull { books[it] }
        override suspend fun filePathBookIdsExcept(audioBookId: String): List<String> {
            val ids = books.values
                .filter { it.id != audioBookId && !it.localPath.isNullOrEmpty() && !it.localPath.startsWith("content:") }
                .map { it.id }
            afterSharerIdsRead?.invoke()
            return ids
        }
        override suspend fun localPathsExcept(audioBookId: String) =
            books.values.filter { it.id != audioBookId }.mapNotNull { it.localPath?.takeIf(String::isNotEmpty) }
        override suspend fun downloadRows() =
            rows.values.map { DownloadOwnerRow(it.audioBookId, it.status.ordinal, it.downloadedBytes) }
        override fun plannedLocation(book: AudioBook, startedWriting: Boolean) =
            DownloadLocation(root, bookDownloadFolder(root, book, startedWriting), true)
        override fun storedLocation(localPath: String) = deleteLocationFor(localPath, "", root)
    }

    private fun folder(root: File, name: String, vararg files: String) = File(root, name).apply {
        mkdirs()
        files.forEach { File(this, it).writeBytes(byteArrayOf(1, 2, 3)) }
    }

    private suspend fun DownloadOwnership.claimFor(reads: FakeReads, downloadId: String, folder: File) =
        claim(
            downloadId = downloadId,
            audioBookId = reads.rows[downloadId]?.audioBookId ?: downloadId.removePrefix("d"),
            folder = folder,
            readRow = { reads.rows[downloadId] },
            writeRow = { reads.rows[it.id] = it },
        )

    /** The engine's completion from its in-memory [row], as DownloadEngine writes it. */
    private suspend fun DownloadOwnership.finish(reads: FakeReads, row: DownloadItem, folder: File): Boolean {
        val committed = commitCompletion(rowExists = { row.id in reads.rows }) {
            reads.rows[row.id] = row.copy(status = DownloadStatus.Completed)
            val book = reads.books.getValue(row.audioBookId)
            reads.books[book.id] = book.copy(isDownloaded = true, localPath = folder.absolutePath)
        }
        release()
        return committed
    }

    private suspend fun DownloadOwnership.noEngineOn(audioBookId: String, act: suspend () -> Unit) =
        actOnceEngineIsOff(audioBookId, rowIsDownloading = { false }, stopEngine = { error("engine was not on $audioBookId") }) {
            act()
        }

    // ─── Reported races ──────────────────────────────────────────────────────

    @Test
    fun `deleting one edition while the other commits its finish keeps the other's audio`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val shared = folder(root, "Herman Melville - Moby-Dick", "a1.mp3", "b1.mp3", "cover.jpg")
        val reads = FakeReads(root)
        val a = AudioBook(
            id = "A", title = "Moby-Dick", author = "Herman Melville",
            isDownloaded = true, localPath = shared.absolutePath, audioFiles = listOf(track("a1.mp3")),
        )
        reads.books["A"] = a
        reads.books["B"] = a.copy(id = "B", isDownloaded = false, localPath = null, audioFiles = listOf(track("b1.mp3")))
        reads.rows["dB"] = DownloadItem(id = "dB", audioBookId = "B", status = DownloadStatus.Queued)
        val ownership = DownloadOwnership()
        val engineRow = ownership.claimFor(reads, "dB", shared)!!

        // B finishes right after delete has looked for books stored there
        // (B has no localPath yet, so it is not one of them).
        var finish: Job? = null
        reads.afterSharerIdsRead = {
            reads.afterSharerIdsRead = null
            finish = launch { ownership.finish(reads, engineRow, shared) }
            yield()
        }
        ownership.noEngineOn("A") { removeDownloadedBookFiles(reads, ownership, a, shared.absolutePath) }
        finish?.join()

        assertTrue("B's audio went with A's delete", File(shared, "b1.mp3").exists())
    }

    @Test
    fun `cancelling a paused book never deletes the folder the engine is really writing`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        // A paused here with a partial track. B started into the same folder,
        // then a sync renamed B, so its row now names another folder while its
        // engine keeps writing to this one.
        val shared = folder(root, "Herman Melville - Moby-Dick", "01.mp3", "b-01.mp3.part")
        val reads = FakeReads(root)
        reads.books["A"] = AudioBook(id = "A", title = "Moby-Dick", author = "Herman Melville")
        reads.books["B"] = AudioBook(id = "B", title = "Moby-Dick", author = "Herman Melville")
        reads.rows["dA"] = DownloadItem(id = "dA", audioBookId = "A", status = DownloadStatus.Paused)
        reads.rows["dB"] = DownloadItem(id = "dB", audioBookId = "B", status = DownloadStatus.Queued)
        val ownership = DownloadOwnership()
        assertNotNull(ownership.claimFor(reads, "dB", shared))
        reads.books["B"] = reads.books.getValue("B").copy(title = "Moby-Dick (Unabridged)")

        ownership.noEngineOn("A") {
            reads.rows.remove("dA")
            removeCancelledPartialFiles(reads, ownership, "A", startedWriting = true)
        }

        assertTrue("B's in-progress track went with A's cancel", File(shared, "b-01.mp3.part").exists())
    }

    @Test
    fun `deleting an edition never deletes the folder the engine is really writing`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val shared = folder(root, "Herman Melville - Moby-Dick", "a1.mp3", "b1.mp3.part")
        val reads = FakeReads(root)
        val a = AudioBook(
            id = "A", title = "Moby-Dick", author = "Herman Melville",
            isDownloaded = true, localPath = shared.absolutePath, audioFiles = listOf(track("a1.mp3")),
        )
        reads.books["A"] = a
        reads.books["B"] = AudioBook(id = "B", title = "Moby-Dick", author = "Herman Melville")
        reads.rows["dB"] = DownloadItem(id = "dB", audioBookId = "B", status = DownloadStatus.Queued)
        val ownership = DownloadOwnership()
        assertNotNull(ownership.claimFor(reads, "dB", shared))
        // A sync renamed B while its engine keeps writing here.
        reads.books["B"] = reads.books.getValue("B").copy(title = "Moby-Dick (Unabridged)")

        ownership.noEngineOn("A") { removeDownloadedBookFiles(reads, ownership, a, shared.absolutePath) }

        assertTrue("B's in-progress track went with A's delete", File(shared, "b1.mp3.part").exists())
    }

    @Test
    fun `a queued download cancelled as the engine picks it never comes back`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val reads = FakeReads(root)
        reads.rows["dA"] = DownloadItem(id = "dA", audioBookId = "A", status = DownloadStatus.Queued)
        reads.rows["dC"] = DownloadItem(id = "dC", audioBookId = "C", status = DownloadStatus.Queued, startedAt = 2)
        val ownership = DownloadOwnership()
        var stopRequested = false

        // The user cancels A right as the engine reads its row. C keeps the
        // drain alive, so nothing else stops it.
        var cancel: Job? = null
        val claimed = ownership.claim(
            downloadId = "dA",
            audioBookId = "A",
            folder = File(root, "A"),
            readRow = {
                val row = reads.rows["dA"]
                cancel = launch {
                    ownership.actOnceEngineIsOff(
                        audioBookId = "A",
                        rowIsDownloading = { reads.rows["dA"]?.status == DownloadStatus.Downloading },
                        stopEngine = { stopRequested = true; true },
                    ) { reads.rows.remove("dA") }
                }
                yield()
                row
            },
            writeRow = { reads.rows[it.id] = it },
        )
        cancel?.join()

        assertNull("the cancelled row came back", reads.rows["dA"])
        assertTrue("the engine kept downloading a cancelled book", claimed == null || stopRequested)
    }

    @Test
    fun `a queued download cancelled before the engine claims it never starts`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val reads = FakeReads(root)
        reads.rows["dA"] = DownloadItem(id = "dA", audioBookId = "A", status = DownloadStatus.Queued)
        val ownership = DownloadOwnership()

        // The drain picked A from an older read, and the cancel got in first.
        ownership.noEngineOn("A") { reads.rows.remove("dA") }
        val claimed = ownership.claimFor(reads, "dA", File(root, "A"))

        assertNull(claimed)
        assertNull(reads.rows["dA"])
        assertNull(ownership.engineClaim)
    }

    @Test
    fun `a download deleted while it finishes is not marked downloaded`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val dir = folder(root, "Herman Melville - Moby-Dick", "01.mp3")
        val reads = FakeReads(root)
        reads.books["A"] = AudioBook(id = "A", title = "Moby-Dick", author = "Herman Melville")
        reads.rows["dA"] = DownloadItem(id = "dA", audioBookId = "A", status = DownloadStatus.Queued)
        val ownership = DownloadOwnership()
        val engineRow = ownership.claimFor(reads, "dA", dir)!!

        // The stop timed out, the row went anyway, and then the engine finished.
        reads.rows.remove("dA")
        val committed = ownership.finish(reads, engineRow, dir)

        assertFalse(committed)
        assertNull(reads.rows["dA"])
        assertNull(reads.books.getValue("A").localPath)
    }

    @Test
    fun `cancel keeps the files when the stop never confirmed`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val dir = folder(root, "Herman Melville - Moby-Dick", "01.mp3", "02.mp3.part")
        val reads = FakeReads(root)
        reads.books["A"] = AudioBook(id = "A", title = "Moby-Dick", author = "Herman Melville")
        reads.rows["dA"] = DownloadItem(id = "dA", audioBookId = "A", status = DownloadStatus.Queued)
        val ownership = DownloadOwnership()
        assertNotNull(ownership.claimFor(reads, "dA", dir))

        // A read stuck on a stalled server: the stop gives up, the engine stays.
        ownership.actOnceEngineIsOff("A", rowIsDownloading = { true }, stopEngine = { false }) {
            reads.rows.remove("dA")
            removeCancelledPartialFiles(reads, ownership, "A", startedWriting = true)
        }

        assertTrue(File(dir, "02.mp3.part").exists())
    }

    // ─── Everyday paths still work ───────────────────────────────────────────

    @Test
    fun `deleting a book nobody shares removes its folder`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val dir = folder(root, "Herman Melville - Moby-Dick", "01.mp3", "02.mp3", "cover.jpg")
        val reads = FakeReads(root)
        val a = AudioBook(
            id = "A", title = "Moby-Dick", author = "Herman Melville", isDownloaded = true,
            localPath = dir.absolutePath, audioFiles = listOf(track("01.mp3", 0), track("02.mp3", 1)),
        )
        reads.books["A"] = a
        val ownership = DownloadOwnership()

        ownership.noEngineOn("A") { removeDownloadedBookFiles(reads, ownership, a, dir.absolutePath) }

        assertFalse(dir.exists())
    }

    @Test
    fun `cancelling a paused book nobody shares removes its partial folder`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val dir = folder(root, "Herman Melville - Moby-Dick", "01.mp3", "02.mp3.part")
        val reads = FakeReads(root)
        reads.books["A"] = AudioBook(id = "A", title = "Moby-Dick", author = "Herman Melville")
        reads.rows["dA"] = DownloadItem(id = "dA", audioBookId = "A", status = DownloadStatus.Paused)
        val ownership = DownloadOwnership()

        ownership.noEngineOn("A") {
            reads.rows.remove("dA")
            removeCancelledPartialFiles(reads, ownership, "A", startedWriting = true)
        }

        assertFalse(dir.exists())
    }

    @Test
    fun `deleting a book while an unrelated one downloads goes ahead at once`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val dir = folder(root, "Herman Melville - Moby-Dick", "01.mp3")
        val other = folder(root, "Jane Austen - Emma", "01.mp3.part")
        val reads = FakeReads(root)
        val a = AudioBook(
            id = "A", title = "Moby-Dick", author = "Herman Melville", isDownloaded = true,
            localPath = dir.absolutePath, audioFiles = listOf(track("01.mp3")),
        )
        reads.books["A"] = a
        reads.books["E"] = AudioBook(id = "E", title = "Emma", author = "Jane Austen")
        reads.rows["dE"] = DownloadItem(id = "dE", audioBookId = "E", status = DownloadStatus.Queued)
        val ownership = DownloadOwnership()
        assertNotNull(ownership.claimFor(reads, "dE", other))

        // The engine stays on E the whole time and never lets go.
        withTimeout(2_000) {
            ownership.noEngineOn("A") { removeDownloadedBookFiles(reads, ownership, a, dir.absolutePath) }
        }

        assertFalse(dir.exists())
        assertTrue(File(other, "01.mp3.part").exists())
    }

    @Test
    fun `the engine on the book is stopped outside the lock so it can let go`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val reads = FakeReads(root)
        reads.rows["dA"] = DownloadItem(id = "dA", audioBookId = "A", status = DownloadStatus.Queued)
        val ownership = DownloadOwnership()
        assertNotNull(ownership.claimFor(reads, "dA", File(root, "A")))

        // The stop waits for the engine to exit, and exiting releases the claim.
        val stop = withTimeout(2_000) {
            ownership.actOnceEngineIsOff(
                audioBookId = "A",
                rowIsDownloading = { false },
                stopEngine = { ownership.release(); true },
            ) { it }
        }

        assertEquals(EngineStop(wasDownloading = true, stopConfirmed = true), stop)
        assertNull(ownership.engineClaim)
    }

    @Test
    fun `a paused or finished row is never claimed`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val reads = FakeReads(root)
        reads.rows["dP"] = DownloadItem(id = "dP", audioBookId = "P", status = DownloadStatus.Paused)
        reads.rows["dD"] = DownloadItem(id = "dD", audioBookId = "D", status = DownloadStatus.Completed)
        val ownership = DownloadOwnership()

        assertNull(ownership.claimFor(reads, "dP", File(root, "P")))
        assertNull(ownership.claimFor(reads, "dD", File(root, "D")))
        assertEquals(DownloadStatus.Paused, reads.rows.getValue("dP").status)
        assertNull(ownership.engineClaim)
    }

    // ─── Each download in its own folder (#49) ───────────────────────────────

    private val editionA = AudioBook(id = "li_editionaaa1", title = "Moby-Dick", author = "Herman Melville")
    private val editionB = AudioBook(id = "li_editionbbb2", title = "Moby-Dick", author = "Herman Melville")
    private val legacyName = "Herman Melville - Moby-Dick"

    @Test
    fun `a new download never lands in another edition's cancelled partial folder`() {
        val root = tempFolder.newFolder("Audiobookshelf")
        // A cancelled partial of edition A, left in the old shared folder.
        folder(root, legacyName, "01.mp3", "02.mp3.part")

        val target = bookDownloadFolder(root, editionB, startedWriting = false)

        assertNotEquals(File(root, legacyName), target)
        assertFalse("B would reuse A's partial track", File(target, "01.mp3").exists())
    }

    @Test
    fun `two editions download side by side into separate folders`() {
        val root = tempFolder.newFolder("Audiobookshelf")
        val a = bookDownloadFolder(root, editionA, startedWriting = false)
        val b = bookDownloadFolder(root, editionB, startedWriting = false)

        assertNotEquals(a, b)
        assertEquals(root, a.parentFile)
        assertEquals(root, b.parentFile)
    }

    @Test
    fun `a download started before its own folder existed resumes in its old folder`() {
        val root = tempFolder.newFolder("Audiobookshelf")
        val old = folder(root, legacyName, "01.mp3")

        assertEquals(old, bookDownloadFolder(root, editionA, startedWriting = true))
    }

    @Test
    fun `a started download that already wrote into its own folder stays there`() {
        val root = tempFolder.newFolder("Audiobookshelf")
        folder(root, legacyName, "01.mp3")
        val own = folder(root, itemDownloadFolderName(editionA.author, editionA.title, editionA.id), "01.mp3")

        assertEquals(own, bookDownloadFolder(root, editionA, startedWriting = true))
    }

    @Test
    fun `a started download with no old folder gets its own`() {
        val root = tempFolder.newFolder("Audiobookshelf")
        val target = bookDownloadFolder(root, editionA, startedWriting = true)
        assertEquals(itemDownloadFolderName(editionA.author, editionA.title, editionA.id), target.name)
    }

    @Test
    fun `deleting one edition while the other downloads removes all of the first one's files`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val reads = FakeReads(root)
        val aDir = bookDownloadFolder(root, editionA, startedWriting = false)
        folder(root, aDir.name, "01.mp3", "cover.jpg")
        val a = editionA.copy(isDownloaded = true, localPath = aDir.absolutePath, audioFiles = listOf(track("01.mp3")))
        reads.books[a.id] = a
        reads.books[editionB.id] = editionB
        reads.rows["dB"] = DownloadItem(id = "dB", audioBookId = editionB.id, status = DownloadStatus.Queued)
        val ownership = DownloadOwnership()
        val bDir = bookDownloadFolder(root, editionB, startedWriting = false)
        assertNotNull(ownership.claimFor(reads, "dB", bDir))
        folder(root, bDir.name, "01.mp3.part")

        ownership.noEngineOn(a.id) { removeDownloadedBookFiles(reads, ownership, a, aDir.absolutePath) }

        assertFalse("A's files were left behind", aDir.exists())
        assertTrue(File(bDir, "01.mp3.part").exists())
    }

    @Test
    fun `deleting an old shared-folder download while a new edition is queued removes it`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val reads = FakeReads(root)
        val old = folder(root, legacyName, "01.mp3", "cover.jpg")
        val a = editionA.copy(isDownloaded = true, localPath = old.absolutePath, audioFiles = listOf(track("01.mp3")))
        reads.books[a.id] = a
        reads.books[editionB.id] = editionB
        reads.rows["dB"] = DownloadItem(id = "dB", audioBookId = editionB.id, status = DownloadStatus.Queued)
        val ownership = DownloadOwnership()

        ownership.noEngineOn(a.id) { removeDownloadedBookFiles(reads, ownership, a, old.absolutePath) }

        assertFalse("A's old folder was kept for a download that will never write there", old.exists())
    }

    @Test
    fun `an old paused download still keeps its shared folder safe from the other edition's delete`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val reads = FakeReads(root)
        val old = folder(root, legacyName, "a1.mp3", "b1.mp3", "b2.mp3.part")
        val a = editionA.copy(isDownloaded = true, localPath = old.absolutePath, audioFiles = listOf(track("a1.mp3")))
        reads.books[a.id] = a
        reads.books[editionB.id] = editionB
        reads.rows["dB"] = DownloadItem(
            id = "dB", audioBookId = editionB.id, status = DownloadStatus.Paused, downloadedBytes = 3,
        )
        val ownership = DownloadOwnership()

        ownership.noEngineOn(a.id) { removeDownloadedBookFiles(reads, ownership, a, old.absolutePath) }

        assertTrue("B's finished track went with A's delete", File(old, "b1.mp3").exists())
        assertTrue(File(old, "b2.mp3.part").exists())
    }

    @Test
    fun `cancelling a new download cleans its own folder and leaves the old shared one alone`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val reads = FakeReads(root)
        // An old cancelled partial nobody tracks any more, from before the upgrade.
        val old = folder(root, legacyName, "01.mp3")
        val own = folder(root, itemDownloadFolderName(editionB.author, editionB.title, editionB.id), "01.mp3", "02.mp3.part")
        reads.books[editionB.id] = editionB
        val ownership = DownloadOwnership()

        ownership.noEngineOn(editionB.id) {
            removeCancelledPartialFiles(reads, ownership, editionB.id, startedWriting = true)
        }

        assertFalse(own.exists())
        assertTrue(File(old, "01.mp3").exists())
    }

    @Test
    fun `cancelling an old paused download cleans its old folder`() = runBlocking {
        val root = tempFolder.newFolder("Audiobookshelf")
        val reads = FakeReads(root)
        val old = folder(root, legacyName, "01.mp3", "02.mp3.part")
        reads.books[editionA.id] = editionA
        val ownership = DownloadOwnership()

        ownership.noEngineOn(editionA.id) {
            removeCancelledPartialFiles(reads, ownership, editionA.id, startedWriting = true)
        }

        assertFalse(old.exists())
    }
}
