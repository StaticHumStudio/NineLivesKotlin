package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.service.local.LocalLibraryScanner
import com.ninelivesaudio.app.service.local.ScannedLocalBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A scan that finds books can still have hit the depth or folder cap along the
 * way. [buildScanOutcome] decides what the settings screen shows for that: the
 * always-visible per-folder summary, and which one banner (success or error)
 * surfaces above it. A books-found scan used to always win the success banner,
 * even when it also carried an actionable cap message like "Pick a more
 * specific folder" that the user never saw.
 */
class ScanOutcomeTest {

    private fun book(id: String) = ScannedLocalBook(
        id = id,
        title = id,
        author = "Author",
        tracks = emptyList(),
    )

    private fun result(
        bookCount: Int = 1,
        skippedCount: Int = 0,
        errorMessages: List<String> = emptyList(),
        foldersScanned: Int = 10,
        archiveFileCount: Int = 0,
    ) = LocalLibraryScanner.ScanResult(
        books = (0 until bookCount).map { book("book$it") },
        skippedCount = skippedCount,
        errorMessages = errorMessages,
        foldersScanned = foldersScanned,
        archiveFileCount = archiveFileCount,
    )

    @Test
    fun `a clean scan with books is a plain success`() {
        val outcome = buildScanOutcome(result(bookCount = 3, skippedCount = 1), countedAs = "imported")

        assertEquals("3 books imported, 1 skipped", outcome.lastScanMessage)
        assertEquals("3 books imported, 1 skipped", outcome.successMessage)
        assertNull(outcome.errorMessage)
    }

    @Test
    fun `a clean scan with zero books explains why in the error banner`() {
        val outcome = buildScanOutcome(result(bookCount = 0, foldersScanned = 42), countedAs = "imported")

        assertNull(outcome.successMessage)
        assertEquals(
            "No books found in 42 folders. Nine Lives looks for folders with audio files " +
                "inside. Check the folder layout guide in Settings.",
            outcome.errorMessage,
        )
    }

    @Test
    fun `a scan with no archives adds no extraction hint`() {
        val outcome = buildScanOutcome(result(bookCount = 2), countedAs = "imported")

        assertEquals("2 books imported", outcome.successMessage)
        assertNull(outcome.errorMessage)
    }

    @Test
    fun `an empty scan with one archive adds the singular extraction hint`() {
        val outcome = buildScanOutcome(
            result(bookCount = 0, foldersScanned = 3, archiveFileCount = 1),
            countedAs = "imported",
        )

        assertNull(outcome.successMessage)
        assertEquals(
            "No books found in 3 folders. Nine Lives looks for folders with audio files " +
                "inside. Check the folder layout guide in Settings.\n\n" +
                "1 archive found. Extract it into a folder to import.",
            outcome.errorMessage,
        )
    }

    @Test
    fun `a books found scan with several archives adds the plural extraction hint`() {
        val outcome = buildScanOutcome(
            result(bookCount = 2, archiveFileCount = 3),
            countedAs = "imported",
        )

        assertEquals(
            "2 books imported\n\n3 archives found. Extract them into folders to import.",
            outcome.successMessage,
        )
        assertNull(outcome.errorMessage)
    }

    @Test
    fun `books found alongside a cap warning surfaces the warning, not a bare success`() {
        val outcome = buildScanOutcome(
            result(
                bookCount = 5,
                errorMessages = listOf("Scan stopped early: more than 1000 folders. Pick a more specific folder."),
            ),
            countedAs = "imported",
        )

        assertEquals("5 books imported", outcome.lastScanMessage)
        assertNull(outcome.successMessage)
        assertEquals(
            "Scan stopped early: more than 1000 folders. Pick a more specific folder.",
            outcome.errorMessage,
        )
    }

    @Test
    fun `multiple warnings collapse to the first message plus a count`() {
        val outcome = buildScanOutcome(
            result(
                bookCount = 2,
                errorMessages = listOf(
                    "Scan stopped early: folders nested deeper than 8 levels were skipped.",
                    "Skipped folder 'Rotten': permission yanked",
                    "Skipped folder 'Also Rotten': permission yanked",
                ),
            ),
            countedAs = "found",
        )

        assertNull(outcome.successMessage)
        assertEquals(
            "Scan stopped early: folders nested deeper than 8 levels were skipped. (+2 more)",
            outcome.errorMessage,
        )
    }

    @Test
    fun `a rescan success carries the rescan-complete prefix but a warning does not`() {
        val clean = buildScanOutcome(result(bookCount = 4), countedAs = "found") { "Rescan complete: $it" }
        assertEquals("Rescan complete: 4 books found", clean.successMessage)

        val capped = buildScanOutcome(
            result(bookCount = 4, errorMessages = listOf("Pick a more specific folder.")),
            countedAs = "found",
        ) { "Rescan complete: $it" }
        assertNull(capped.successMessage)
        assertEquals("Pick a more specific folder.", capped.errorMessage)
    }
}
