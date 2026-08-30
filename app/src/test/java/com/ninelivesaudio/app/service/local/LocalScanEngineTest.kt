package com.ninelivesaudio.app.service.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Traversal and grouping tests for [LocalScanEngine] against issue #17: nested
 * libraries (Author/Book, multi-disc CD1/CD2) previously imported zero books.
 * All fakes are plain JVM, no Android framework involved.
 */
class LocalScanEngineTest {

    private val rootUri = "content://tree/root"

    // ─── Fakes ─────────────────────────────────────────────────────────────

    private open class FakeNode(
        override val name: String?,
        override val isDirectory: Boolean,
        override val uriString: String,
        override val sizeBytes: Long = 0L,
        override val mimeType: String? = null,
        private val childNodes: List<ScanNode> = emptyList(),
    ) : ScanNode {
        override fun children(): List<ScanNode> = childNodes
    }

    private class ThrowingNode(name: String?, uriString: String) :
        FakeNode(name = name, isDirectory = true, uriString = uriString) {
        override fun children(): List<ScanNode> = throw RuntimeException("permission yanked")
    }

    private fun file(name: String?, uri: String, size: Long = 100L): ScanNode =
        FakeNode(name = name, isDirectory = false, uriString = uri, sizeBytes = size)

    private fun dir(name: String?, uri: String, children: List<ScanNode> = emptyList()): ScanNode =
        FakeNode(name = name, isDirectory = true, uriString = uri, childNodes = children)

    private class FakeMetadataSource(
        private val metadata: Map<String, LocalMetadataExtractor.TrackMetadata> = emptyMap(),
    ) : ScanMetadataSource {
        override fun extract(uriString: String) = metadata[uriString]
        override fun persistFolderCover(coverUriString: String?, bookId: String): String? = coverUriString
        override fun extractEmbeddedCover(uriString: String, bookId: String): String? = null
    }

    private fun engine(metadata: Map<String, LocalMetadataExtractor.TrackMetadata> = emptyMap()) =
        LocalScanEngine(FakeMetadataSource(metadata))

    // ─── R1 / R2 / R6: Author/Book layout ──────────────────────────────────

    @Test
    fun `author book files makes one book per book folder with author from folder`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Terry Pratchett", "$rootUri/Terry Pratchett", children = listOf(
                        dir(
                            "Guards! Guards!", "$rootUri/Terry Pratchett/Guards! Guards!", children = listOf(
                                file("chapter01.mp3", "$rootUri/Terry Pratchett/Guards! Guards!/chapter01.mp3"),
                                file("chapter02.mp3", "$rootUri/Terry Pratchett/Guards! Guards!/chapter02.mp3"),
                            )
                        )
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(1, result.books.size)
        val book = result.books.single()
        assertEquals("Guards! Guards!", book.title)
        assertEquals("Terry Pratchett", book.author)
        assertEquals(2, book.tracks.size)
    }

    @Test
    fun `depth one book id matches the old pre-recursion hash formula`() {
        val uri = "content://tree/1234"
        val root = dir(
            null, uri, children = listOf(
                dir(
                    "BookFolder", "$uri/BookFolder", children = listOf(
                        file("track.mp3", "$uri/BookFolder/track.mp3"),
                    )
                )
            )
        )

        val result = engine().scan(root, uri)

        // sha256("content://tree/1234/BookFolder"), pinned so existing imports keep their id.
        val expectedId = "local_book_8d62dbc1b82b3da1fe5513c1bd187aa5ca401f7e106c1daf662dc60130d9786c"
        assertEquals(1, result.books.size)
        assertEquals(expectedId, result.books.single().id)
    }

    // ─── R4: disc grouping ──────────────────────────────────────────────────

    @Test
    fun `book with CD1 and CD2 merges into one book in disc-major order`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Book Title", "$rootUri/Book Title", children = listOf(
                        dir(
                            "CD1", "$rootUri/Book Title/CD1", children = listOf(
                                file("02.mp3", "$rootUri/Book Title/CD1/02.mp3"),
                                file("01.mp3", "$rootUri/Book Title/CD1/01.mp3"),
                            )
                        ),
                        dir(
                            "CD2", "$rootUri/Book Title/CD2", children = listOf(
                                file("01.mp3", "$rootUri/Book Title/CD2/01.mp3"),
                            )
                        ),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(1, result.books.size)
        val book = result.books.single()
        assertEquals("Book Title", book.title)
        assertEquals(
            listOf(
                "$rootUri/Book Title/CD1/01.mp3",
                "$rootUri/Book Title/CD1/02.mp3",
                "$rootUri/Book Title/CD2/01.mp3",
            ),
            book.tracks.map { it.uri },
        )
        assertEquals(listOf(0, 1, 2), book.tracks.map { it.index })
        val expectedId = "local_book_" + sha256Hex("$rootUri/Book Title")
        assertEquals(expectedId, book.id)
    }

    @Test
    fun `disc track numbers reset per disc but merged order stays disc-major`() {
        fun meta(track: Int) = LocalMetadataExtractor.TrackMetadata(trackNumber = track)
        val cd1t1 = "$rootUri/Book/CD1/a.mp3"
        val cd1t2 = "$rootUri/Book/CD1/b.mp3"
        val cd2t1 = "$rootUri/Book/CD2/a.mp3"
        val cd2t2 = "$rootUri/Book/CD2/b.mp3"

        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Book", "$rootUri/Book", children = listOf(
                        dir("CD1", "$rootUri/Book/CD1", children = listOf(file("a.mp3", cd1t1), file("b.mp3", cd1t2))),
                        dir("CD2", "$rootUri/Book/CD2", children = listOf(file("a.mp3", cd2t1), file("b.mp3", cd2t2))),
                    )
                )
            )
        )

        val result = engine(
            mapOf(cd1t1 to meta(1), cd1t2 to meta(2), cd2t1 to meta(1), cd2t2 to meta(2))
        ).scan(root, rootUri)

        assertEquals(1, result.books.size)
        // Never interleaved by trackNumber (that would read cd1t1, cd2t1, cd1t2, cd2t2).
        assertEquals(listOf(cd1t1, cd1t2, cd2t1, cd2t2), result.books.single().tracks.map { it.uri })
    }

    @Test
    fun `a single CD1 subfolder still merges upward`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Book", "$rootUri/Book", children = listOf(
                        dir(
                            "CD1", "$rootUri/Book/CD1", children = listOf(
                                file("a.mp3", "$rootUri/Book/CD1/a.mp3"),
                            )
                        )
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(1, result.books.size)
        assertEquals("Book", result.books.single().title)
    }

    @Test
    fun `non-disc-named subfolders do not merge and become two books`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Book", "$rootUri/Book", children = listOf(
                        dir("Side A", "$rootUri/Book/Side A", children = listOf(file("a.mp3", "$rootUri/Book/Side A/a.mp3"))),
                        dir("Side B", "$rootUri/Book/Side B", children = listOf(file("b.mp3", "$rootUri/Book/Side B/b.mp3"))),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(2, result.books.size)
        assertEquals(setOf("Side A", "Side B"), result.books.map { it.title }.toSet())
        // Depth 2, no tags: author falls back to the parent folder name.
        assertTrue(result.books.all { it.author == "Book" })
    }

    // ─── R3: recursion continues past a book ───────────────────────────────

    @Test
    fun `folder with direct audio and an audio subfolder yields two books`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "BookFolder", "$rootUri/BookFolder", children = listOf(
                        file("chapter00.mp3", "$rootUri/BookFolder/chapter00.mp3"),
                        dir(
                            "Extras", "$rootUri/BookFolder/Extras", children = listOf(
                                file("bonus.mp3", "$rootUri/BookFolder/Extras/bonus.mp3"),
                            )
                        ),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(2, result.books.size)
        assertEquals(setOf("BookFolder", "Extras"), result.books.map { it.title }.toSet())
    }

    // ─── R1: root loose files ───────────────────────────────────────────────

    @Test
    fun `root loose files each become a single-file book with the old id formula`() {
        val uri = "content://tree/root8"
        val root = dir(
            null, uri, children = listOf(
                file("chapter1.mp3", "$uri/chapter1.mp3"),
                file("chapter2.mp3", "$uri/chapter2.mp3"),
            )
        )

        val result = engine().scan(root, uri)

        assertEquals(2, result.books.size)
        val ids = result.books.map { it.id }.toSet()
        assertTrue("local_book_" + sha256Hex("$uri/chapter1.mp3") in ids)
        assertTrue("local_book_" + sha256Hex("$uri/chapter2.mp3") in ids)
        assertTrue(result.books.all { it.tracks.size == 1 })
    }

    // ─── R8: hidden entries ─────────────────────────────────────────────────

    @Test
    fun `hidden folders and files are ignored at every depth`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Author", "$rootUri/Author", children = listOf(
                        dir(
                            ".Hidden", "$rootUri/Author/.Hidden", children = listOf(
                                file("secret.mp3", "$rootUri/Author/.Hidden/secret.mp3"),
                            )
                        ),
                        dir(
                            "Book", "$rootUri/Author/Book", children = listOf(
                                file("track.mp3", "$rootUri/Author/Book/track.mp3"),
                                file(".hidden.mp3", "$rootUri/Author/Book/.hidden.mp3"),
                                file(null, "$rootUri/Author/Book/unnamed"),
                            )
                        ),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(1, result.books.size)
        val book = result.books.single()
        assertEquals("Book", book.title)
        assertEquals(1, book.tracks.size)
        assertEquals("track.mp3", book.tracks.single().filename)
    }

    // ─── R7: caps ───────────────────────────────────────────────────────────

    @Test
    fun `a ten deep chain terminates, reports the depth cap, and shallow books still import`() {
        fun chain(depth: Int): ScanNode {
            if (depth == 10) {
                return dir(
                    "Deep10", "$rootUri/deep10", children = listOf(
                        file("buried.mp3", "$rootUri/deep10/buried.mp3")
                    )
                )
            }
            return dir("Deep$depth", "$rootUri/deep$depth", children = listOf(chain(depth + 1)))
        }

        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Shallow", "$rootUri/Shallow", children = listOf(
                        file("chapter.mp3", "$rootUri/Shallow/chapter.mp3"),
                    )
                ),
                chain(1),
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(1, result.books.size)
        assertEquals("Shallow", result.books.single().title)
        assertTrue(result.errorMessages.any { it.contains("${LocalScanEngine.MAX_SCAN_DEPTH}") })
        assertTrue(result.skippedCount >= 1)
    }

    @Test
    fun `the folder cap is respected and reported`() {
        val total = LocalScanEngine.MAX_FOLDERS_SCANNED + 6
        val subfolders = (0 until total).map { i ->
            dir(
                "Book$i", "$rootUri/Book$i", children = listOf(
                    file("track.mp3", "$rootUri/Book$i/track.mp3"),
                )
            )
        }
        val root = dir(null, rootUri, children = subfolders)

        val result = engine().scan(root, rootUri)

        // Root itself counts as one visited folder, so only MAX - 1 subfolders get in
        // before the cap trips.
        assertEquals(LocalScanEngine.MAX_FOLDERS_SCANNED - 1, result.books.size)
        assertEquals(1, result.errorMessages.count { it.contains("${LocalScanEngine.MAX_FOLDERS_SCANNED}") })
        assertTrue(result.skippedCount > 0)
    }

    // ─── R9 / empty tree ────────────────────────────────────────────────────

    @Test
    fun `an empty tree yields zero books, counts folders scanned, and does not crash`() {
        val root = dir(null, rootUri, children = emptyList())

        val result = engine().scan(root, rootUri)

        assertTrue(result.books.isEmpty())
        assertTrue(result.foldersScanned >= 1)
        assertEquals(0, result.skippedCount)
    }

    // ─── Sorting ────────────────────────────────────────────────────────────

    @Test
    fun `natural filename order sorts 2 mp3 before 10 mp3`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Book", "$rootUri/Book", children = listOf(
                        file("10.mp3", "$rootUri/Book/10.mp3"),
                        file("2.mp3", "$rootUri/Book/2.mp3"),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(listOf("2.mp3", "10.mp3"), result.books.single().tracks.map { it.filename })
    }

    @Test
    fun `mixed case extensions like dot MP3 are recognized as audio`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Book", "$rootUri/Book", children = listOf(
                        file("track.MP3", "$rootUri/Book/track.MP3"),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(1, result.books.size)
        assertEquals(1, result.books.single().tracks.size)
    }

    // ─── R10: per-folder error isolation ───────────────────────────────────

    @Test
    fun `an exception in one folder is isolated and does not abort the rest of the scan`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Good", "$rootUri/Good", children = listOf(
                        file("track.mp3", "$rootUri/Good/track.mp3"),
                    )
                ),
                ThrowingNode("Bad", "$rootUri/Bad"),
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(1, result.books.size)
        assertEquals("Good", result.books.single().title)
        assertTrue(result.errorMessages.any { it.contains("Bad") })
        assertTrue(result.skippedCount >= 1)
    }

    @Test
    fun `null-named root child is treated as hidden and never becomes a book`() {
        val root = dir(
            null, rootUri, children = listOf(
                file(null, "$rootUri/unnamed.mp3"),
            )
        )

        val result = engine().scan(root, rootUri)

        assertTrue(result.books.isEmpty())
        assertNull(result.books.firstOrNull())
        assertFalse(result.errorMessages.any { it.contains("null") })
    }

    private fun sha256Hex(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
