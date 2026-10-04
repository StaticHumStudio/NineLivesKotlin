package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Deleting a download removes this book's files and nothing else (#49). Two
 * editions can share one "Author - Title" folder, a user-picked root may hold
 * the user's own audio, and a stored path can point anywhere. Every doubt
 * keeps files, just like cancel cleanup.
 */
class DownloadDeleteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun root(): File = tempFolder.newFolder("Audiobookshelf")

    private fun bookFolder(root: File, vararg files: String): File =
        File(root, "Herman Melville - Moby-Dick").apply {
            mkdirs()
            files.forEach { File(this, it).writeBytes(byteArrayOf(1, 2, 3)) }
        }

    private fun delete(
        root: File,
        folder: File,
        ownFileNames: List<String>,
        appOwned: Boolean = true,
        sharers: List<FolderSharer> = emptyList(),
        otherDownloadFolders: List<File> = emptyList(),
    ) = deleteDownloadFiles(
        location = DownloadLocation(root, folder, appOwned),
        ownFileNames = ownFileNames,
        sharers = sharers,
        otherDownloadFolders = otherDownloadFolders,
    )

    // ─── Shared folders (#49) ────────────────────────────────────────────────

    @Test
    fun `deleting one of two editions that share every file keeps the other's files`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3", "02.mp3", "cover.jpg")
        val other = FolderSharer(folder.absolutePath, listOf("01.mp3", "02.mp3"))

        delete(root, folder, ownFileNames = listOf("01.mp3", "02.mp3"), sharers = listOf(other))

        assertTrue(File(folder, "01.mp3").exists())
        assertTrue(File(folder, "02.mp3").exists())
        assertTrue(File(folder, "cover.jpg").exists())
    }

    @Test
    fun `deleting one edition takes only its own files from a shared folder`() {
        val root = root()
        val folder = bookFolder(root, "Part 1.mp3", "Part 1.mp3.part", "Track 01.m4b", "cover.jpg")
        val other = FolderSharer(folder.absolutePath, listOf("Track 01.m4b"))

        val decision = delete(root, folder, ownFileNames = listOf("Part 1.mp3"), sharers = listOf(other))

        assertTrue(decision is CancelCleanupDecision.Delete)
        assertFalse(File(folder, "Part 1.mp3").exists())
        assertFalse(File(folder, "Part 1.mp3.part").exists())
        // The other edition's audio and the cover its row points at both stay.
        assertTrue(File(folder, "Track 01.m4b").exists())
        assertTrue(File(folder, "cover.jpg").exists())
        assertTrue(folder.exists())
    }

    @Test
    fun `a sharer stored as a file uri with a trailing slash still counts`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3")
        val other = FolderSharer(folder.toURI().toString(), listOf("01.mp3"))

        delete(root, folder, ownFileNames = listOf("01.mp3"), sharers = listOf(other))

        assertTrue(File(folder, "01.mp3").exists())
    }

    @Test
    fun `a sharer whose track list is unknown keeps everything`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3", "02.mp3")
        val other = FolderSharer(folder.absolutePath, fileNames = null)

        val decision = delete(root, folder, ownFileNames = listOf("01.mp3"), sharers = listOf(other))

        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.SHARED), decision)
        assertTrue(File(folder, "01.mp3").exists())
        assertTrue(File(folder, "02.mp3").exists())
    }

    @Test
    fun `a book path above or inside the folder keeps everything`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3")

        val above = delete(root, folder, listOf("01.mp3"), sharers = listOf(FolderSharer(root.absolutePath, listOf("x.mp3"))))
        val inside = delete(
            root, folder, listOf("01.mp3"),
            sharers = listOf(FolderSharer(File(folder, "Disc 1").absolutePath, listOf("x.mp3"))),
        )

        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.SHARED), above)
        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.SHARED), inside)
        assertTrue(File(folder, "01.mp3").exists())
    }

    @Test
    fun `a download still writing to the folder keeps everything`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3")

        val decision = delete(root, folder, listOf("01.mp3"), otherDownloadFolders = listOf(File(folder.path)))

        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.SHARED), decision)
        assertTrue(File(folder, "01.mp3").exists())
    }

    @Test
    fun `a sibling book's folder is never touched`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3", "cover.jpg")
        val sibling = File(root, "Other Author - Other Book").apply { mkdirs() }
        File(sibling, "01.mp3").writeBytes(byteArrayOf(9))

        delete(root, folder, listOf("01.mp3"), sharers = listOf(FolderSharer(sibling.absolutePath, listOf("01.mp3"))))

        assertFalse(folder.exists())
        assertTrue(File(sibling, "01.mp3").exists())
    }

    // ─── A folder only this book uses ────────────────────────────────────────

    @Test
    fun `a folder only this book uses goes whole`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3", "02.mp3", "cover.jpg")

        delete(root, folder, ownFileNames = listOf("01.mp3", "02.mp3"))

        assertFalse(folder.exists())
        assertTrue(root.exists())
    }

    @Test
    fun `a subfolder inside the book folder is never recursed into`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3", "cover.jpg")
        val nested = File(folder, "extras").apply { mkdirs() }
        File(nested, "bonus.mp3").writeBytes(byteArrayOf(4))

        delete(root, folder, ownFileNames = listOf("01.mp3"))

        assertFalse(File(folder, "01.mp3").exists())
        assertTrue(File(nested, "bonus.mp3").exists())
        assertTrue(folder.exists())
    }

    // ─── User-picked folder ──────────────────────────────────────────────────

    @Test
    fun `a user-picked root keeps every file`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3", "cover.jpg")

        val decision = delete(root, folder, listOf("01.mp3"), appOwned = false)

        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.USER_FOLDER), decision)
        assertTrue(File(folder, "01.mp3").exists())
        assertTrue(File(folder, "cover.jpg").exists())
    }

    // ─── Root containment ────────────────────────────────────────────────────

    @Test
    fun `a stored path that escapes the root is refused`() {
        val root = root()
        val outside = tempFolder.newFolder("Someone Else's Music").apply {
            File(this, "01.mp3").writeBytes(byteArrayOf(5))
        }
        val escaping = File(root, "../Someone Else's Music")

        val decision = delete(root, escaping, listOf("01.mp3"))

        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT), decision)
        assertTrue(File(outside, "01.mp3").exists())
    }

    @Test
    fun `the root itself is refused`() {
        val root = root()
        bookFolder(root, "01.mp3")
        File(root, "loose.mp3").writeBytes(byteArrayOf(6))

        val decision = delete(root, root, listOf("loose.mp3"))

        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT), decision)
        assertTrue(File(root, "loose.mp3").exists())
        assertTrue(File(root, "Herman Melville - Moby-Dick/01.mp3").exists())
    }

    @Test
    fun `a folder nested deeper than one level is refused`() {
        val root = root()
        val deep = File(bookFolder(root), "Disc 1").apply { mkdirs() }
        File(deep, "01.mp3").writeBytes(byteArrayOf(7))

        val decision = delete(root, deep, listOf("01.mp3"))

        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT), decision)
        assertTrue(File(deep, "01.mp3").exists())
    }

    @Test
    fun `a symlinked book folder is never followed`() {
        val root = root()
        val target = tempFolder.newFolder("elsewhere").apply { File(this, "01.mp3").writeBytes(byteArrayOf(8)) }
        val link = File(root, "Herman Melville - Moby-Dick")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        val decision = delete(root, link, listOf("01.mp3"))

        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT), decision)
        assertTrue(File(target, "01.mp3").exists())
    }

    @Test
    fun `a missing folder is a no-op`() {
        val root = root()
        val decision = delete(root, File(root, "Gone - Gone"), listOf("01.mp3"))
        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.NO_FOLDER), decision)
    }

    // ─── Downloads that still own a folder ──────────────────────────────────

    private fun row(bookId: String, status: DownloadStatus) = DownloadOwnerRow(bookId, status.ordinal)

    @Test
    fun `an edition whose row says Completed before its local copy is saved still owns the folder`() {
        // The engine writes Completed, then fetches the cover, then saves
        // localPath. Deleting the other edition in that window must not
        // count this one as finished.
        val owners = unfinishedDownloadOwners(
            rows = listOf(row("A", DownloadStatus.Completed), row("B", DownloadStatus.Completed)),
            booksWithLocalCopy = setOf("A"),
            excludeBookId = "A",
            engineBookId = null,
        )
        assertEquals(listOf("B"), owners)
    }

    @Test
    fun `the book the engine is on owns its folder even with no row`() {
        val owners = unfinishedDownloadOwners(
            rows = emptyList(),
            booksWithLocalCopy = emptySet(),
            excludeBookId = "A",
            engineBookId = "B",
        )
        assertEquals(listOf("B"), owners)
    }

    @Test
    fun `unfinished rows own their folder and finished ones with a local copy do not`() {
        val owners = unfinishedDownloadOwners(
            rows = listOf(
                row("A", DownloadStatus.Queued),
                row("B", DownloadStatus.Paused),
                row("C", DownloadStatus.Failed),
                row("D", DownloadStatus.Completed),
                row("E", DownloadStatus.Downloading),
            ),
            booksWithLocalCopy = setOf("D"),
            excludeBookId = "A",
            engineBookId = "A",
        )
        assertEquals(listOf("B", "C", "E"), owners)
    }

    @Test
    fun `deleting one edition while the other is finishing keeps the other's audio`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3", "02.mp3")
        val owners = unfinishedDownloadOwners(
            rows = listOf(row("B", DownloadStatus.Completed)),
            booksWithLocalCopy = emptySet(),
            excludeBookId = "A",
            engineBookId = "B",
        )
        // B has no localPath yet, so it is not a sharer. Only its ownership protects it.
        val ownerFolders = owners.map { folder }

        val decision = delete(root, folder, listOf("01.mp3", "02.mp3"), otherDownloadFolders = ownerFolders)

        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.SHARED), decision)
        assertTrue(File(folder, "01.mp3").exists())
        assertTrue(File(folder, "02.mp3").exists())
    }

    // ─── Which root a stored path belongs to ─────────────────────────────────

    @Test
    fun `a stored path in app storage is app owned even with another root configured`() {
        val appRoot = root()
        val userRoot = tempFolder.newFolder("MyBooks")
        val folder = bookFolder(appRoot, "01.mp3")

        val location = deleteLocationFor(folder.absolutePath, userRoot.absolutePath, appRoot)!!

        assertTrue(location.rootIsAppOwned)
        assertEquals(appRoot.canonicalFile, location.root.canonicalFile)
    }

    @Test
    fun `a stored path in a user-picked root is not app owned`() {
        val appRoot = root()
        val userRoot = tempFolder.newFolder("MyBooks")
        val folder = File(userRoot, "Author - Title").apply { mkdirs() }

        val location = deleteLocationFor(folder.absolutePath, userRoot.absolutePath, appRoot)!!

        assertFalse(location.rootIsAppOwned)
    }

    @Test
    fun `a content uri or blank path has nothing to delete`() {
        val appRoot = root()
        assertNull(deleteLocationFor("content://com.android.externalstorage/tree/x", "", appRoot))
        assertNull(deleteLocationFor("  ", "", appRoot))
    }

    @Test
    fun `a stored file uri resolves to its folder`() {
        val appRoot = root()
        val folder = bookFolder(appRoot, "01.mp3")

        val location = deleteLocationFor(folder.toURI().toString(), "", appRoot)!!

        assertEquals(folder.canonicalFile, location.folder.canonicalFile)
        assertTrue(location.rootIsAppOwned)
    }
}
