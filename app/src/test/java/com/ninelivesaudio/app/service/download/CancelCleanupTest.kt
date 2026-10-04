package com.ninelivesaudio.app.service.download

import com.ninelivesaudio.app.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * A cancelled download cleans up its partial folder (#51), and only that
 * folder, and only when nothing else claims it (#49). Every doubt keeps files.
 */
class CancelCleanupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun root(): File = tempFolder.newFolder("Audiobookshelf")

    private fun bookFolder(root: File, vararg files: String): File =
        File(root, "Herman Melville - Moby-Dick").apply {
            mkdirs()
            files.forEach { File(this, it).writeBytes(byteArrayOf(1, 2, 3)) }
        }

    private fun decide(
        root: File,
        folder: File,
        appOwned: Boolean = true,
        downloaded: Boolean = false,
        otherBookPaths: List<String> = emptyList(),
        otherDownloadFolders: List<File> = emptyList(),
    ) = decideCancelCleanup(
        DownloadLocation(root, folder, appOwned),
        downloaded,
        otherBookPaths,
        otherDownloadFolders,
    )

    // ─── Deletes ─────────────────────────────────────────────────────────────

    @Test
    fun `a contained folder in app storage is deleted whole`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3", "02.mp3.part", "cover.jpg")

        val decision = decide(root, folder) as CancelCleanupDecision.Delete
        assertEquals(setOf("01.mp3", "02.mp3.part", "cover.jpg"), decision.files.map { it.name }.toSet())
        assertTrue(decision.removeFolder)

        assertEquals(3, applyCancelCleanup(decision))
        assertFalse(folder.exists())
        assertTrue(root.exists())
    }

    @Test
    fun `a sibling download's folder survives the cleanup`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3")
        val sibling = File(root, "Other Author - Other Book").apply { mkdirs() }
        File(sibling, "01.mp3").writeBytes(byteArrayOf(9))

        val decision = decide(root, folder, otherBookPaths = listOf(sibling.absolutePath))
        applyCancelCleanup(decision as CancelCleanupDecision.Delete)

        assertFalse(folder.exists())
        assertTrue(File(sibling, "01.mp3").exists())
    }

    @Test
    fun `a user-chosen root only loses part files`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3", "02.mp3.part")

        val decision = decide(root, folder, appOwned = false) as CancelCleanupDecision.Delete
        assertEquals(listOf("02.mp3.part"), decision.files.map { it.name })
        assertFalse(decision.removeFolder)

        applyCancelCleanup(decision)
        assertTrue(File(folder, "01.mp3").exists())
        assertFalse(File(folder, "02.mp3.part").exists())
    }

    @Test
    fun `a file that shows up after the decision keeps the folder`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3")
        val decision = decide(root, folder) as CancelCleanupDecision.Delete

        File(folder, "late.mp3").writeBytes(byteArrayOf(4))
        applyCancelCleanup(decision)

        assertTrue(File(folder, "late.mp3").exists())
        assertFalse(File(folder, "01.mp3").exists())
    }

    // ─── Shared path (#49) ───────────────────────────────────────────────────

    @Test
    fun `a folder another book row points at is kept`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3")

        assertEquals(
            CancelCleanupDecision.Keep(CancelKeepReason.SHARED),
            decide(root, folder, otherBookPaths = listOf(folder.absolutePath)),
        )
        assertTrue(File(folder, "01.mp3").exists())
    }

    @Test
    fun `a shared folder written as a file uri or with a trailing slash is still kept`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3")

        assertEquals(
            CancelCleanupDecision.Keep(CancelKeepReason.SHARED),
            decide(root, folder, otherBookPaths = listOf(folder.toURI().toString())),
        )
        assertEquals(
            CancelCleanupDecision.Keep(CancelKeepReason.SHARED),
            decide(root, folder, otherBookPaths = listOf(folder.absolutePath + "/")),
        )
    }

    @Test
    fun `a book path inside the folder or above it keeps the folder`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3")

        assertEquals(
            CancelCleanupDecision.Keep(CancelKeepReason.SHARED),
            decide(root, folder, otherBookPaths = listOf(File(folder, "01.mp3").absolutePath)),
        )
        assertEquals(
            CancelCleanupDecision.Keep(CancelKeepReason.SHARED),
            decide(root, folder, otherBookPaths = listOf(root.absolutePath)),
        )
    }

    @Test
    fun `a folder another queued download would write to is kept`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3")

        assertEquals(
            CancelCleanupDecision.Keep(CancelKeepReason.SHARED),
            decide(root, folder, otherDownloadFolders = listOf(File(root, folder.name))),
        )
    }

    @Test
    fun `content uris from a local import do not block the cleanup`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3")

        val decision = decide(
            root,
            folder,
            otherBookPaths = listOf("content://com.android.externalstorage.documents/tree/primary%3AAudiobooks"),
        )
        assertTrue(decision is CancelCleanupDecision.Delete)
    }

    // ─── Containment ─────────────────────────────────────────────────────────

    @Test
    fun `a folder that escapes the root is never deleted`() {
        val root = root()
        val outside = tempFolder.newFolder("Music", "My Own Audiobooks")
        File(outside, "keep.mp3").writeBytes(byteArrayOf(7))

        // A title of ".." sanitizes to "..", so the engine path climbs out.
        val climbed = File(root, "../Music/My Own Audiobooks")
        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT), decide(root, climbed))
        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT), decide(root, outside))
        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT), decide(root, File(root, "..")))
        assertTrue(File(outside, "keep.mp3").exists())
    }

    @Test
    fun `the root itself is never deleted`() {
        val root = root()
        File(root, "loose.mp3").writeBytes(byteArrayOf(1))

        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT), decide(root, File(root, ".")))
        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT), decide(root, root))
    }

    @Test
    fun `a folder nested deeper than one level is not touched`() {
        val root = root()
        val nested = File(root, "a/b").apply { mkdirs() }

        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT), decide(root, nested))
    }

    @Test
    fun `a symlinked book folder pointing outside the root is never followed`() {
        val root = root()
        val outside = tempFolder.newFolder("elsewhere")
        File(outside, "keep.mp3").writeBytes(byteArrayOf(7))
        val link = File(root, "Herman Melville - Moby-Dick")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertEquals(CancelCleanupDecision.Keep(CancelKeepReason.OUTSIDE_ROOT), decide(root, link))
        assertTrue(File(outside, "keep.mp3").exists())
    }

    @Test
    fun `a symlink or subfolder inside the book folder keeps everything`() {
        val root = root()
        val outside = tempFolder.newFolder("elsewhere")
        File(outside, "keep.mp3").writeBytes(byteArrayOf(7))

        val withLink = bookFolder(root, "01.mp3")
        Files.createSymbolicLink(File(withLink, "sneaky").toPath(), outside.toPath())
        assertEquals(
            CancelCleanupDecision.Keep(CancelKeepReason.UNEXPECTED_CONTENTS),
            decide(root, withLink),
        )

        val withSubdir = File(root, "Another - Book").apply { mkdirs() }
        File(withSubdir, "extras").mkdirs()
        assertEquals(
            CancelCleanupDecision.Keep(CancelKeepReason.UNEXPECTED_CONTENTS),
            decide(root, withSubdir),
        )
        assertTrue(File(outside, "keep.mp3").exists())
    }

    // ─── Nothing to do ───────────────────────────────────────────────────────

    @Test
    fun `a downloaded book is left alone`() {
        val root = root()
        val folder = bookFolder(root, "01.mp3")

        assertEquals(
            CancelCleanupDecision.Keep(CancelKeepReason.BOOK_DOWNLOADED),
            decide(root, folder, downloaded = true),
        )
    }

    @Test
    fun `a missing folder is a no-op`() {
        val root = root()

        assertEquals(
            CancelCleanupDecision.Keep(CancelKeepReason.NO_FOLDER),
            decide(root, File(root, "Never Started")),
        )
    }

    // ─── When cancel may touch files at all ──────────────────────────────────

    @Test
    fun `files are touched only once the engine is provably off the book`() {
        val downloading = DownloadStatus.Downloading.ordinal
        assertTrue(cancelMayTouchFiles(downloading, wasDownloading = true, stopConfirmed = true))
        assertFalse(cancelMayTouchFiles(downloading, wasDownloading = true, stopConfirmed = false))
        assertTrue(cancelMayTouchFiles(DownloadStatus.Paused.ordinal, wasDownloading = false, stopConfirmed = false))
        assertTrue(cancelMayTouchFiles(DownloadStatus.Failed.ordinal, wasDownloading = false, stopConfirmed = false))
        assertFalse(cancelMayTouchFiles(DownloadStatus.Queued.ordinal, wasDownloading = false, stopConfirmed = false))
        assertFalse(cancelMayTouchFiles(DownloadStatus.Preparing.ordinal, wasDownloading = false, stopConfirmed = false))
        assertFalse(cancelMayTouchFiles(DownloadStatus.Completed.ordinal, wasDownloading = false, stopConfirmed = false))
    }

    // ─── Root resolution ─────────────────────────────────────────────────────

    @Test
    fun `the default root is app owned and a configured one is not`() {
        val defaultRoot = tempFolder.newFolder("app", "Audiobookshelf")
        val chosen = tempFolder.newFolder("sdcard", "Audiobooks")

        assertEquals(defaultRoot to true, resolveDownloadRoot("", defaultRoot))
        assertEquals(chosen.canonicalFile to false, resolveDownloadRoot(chosen.absolutePath, defaultRoot))
        assertEquals(defaultRoot.canonicalFile to true, resolveDownloadRoot(defaultRoot.absolutePath, defaultRoot))
    }

    @Test
    fun `a configured system dir falls back to the app owned default`() {
        val defaultRoot = tempFolder.newFolder("app", "Audiobookshelf")

        val (root, appOwned) = resolveDownloadRoot("/proc/self", defaultRoot)
        assertTrue(root === defaultRoot)
        assertTrue(appOwned)
    }
}
