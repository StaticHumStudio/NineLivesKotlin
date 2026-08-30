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
        var childrenCallCount = 0
            private set

        override fun children(): List<ScanNode> {
            childrenCallCount++
            return childNodes
        }
    }

    private class ThrowingNode(name: String?, uriString: String) :
        FakeNode(name = name, isDirectory = true, uriString = uriString) {
        override fun children(): List<ScanNode> = throw RuntimeException("permission yanked")
    }

    private fun file(name: String?, uri: String, size: Long = 100L): ScanNode =
        FakeNode(name = name, isDirectory = false, uriString = uri, sizeBytes = size)

    private fun dir(name: String?, uri: String, children: List<ScanNode> = emptyList()): FakeNode =
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
    fun `prefixed disc folders merge into the parent book in disc-major order`() {
        val cd1Track1 = "$rootUri/The Hobbit/The Hobbit CD1/01.mp3"
        val cd1Track2 = "$rootUri/The Hobbit/The Hobbit CD1/02.mp3"
        val cd2Track1 = "$rootUri/The Hobbit/The Hobbit CD2/01.mp3"
        val cd2Track2 = "$rootUri/The Hobbit/The Hobbit CD2/02.mp3"
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "The Hobbit", "$rootUri/The Hobbit", children = listOf(
                        dir(
                            "The Hobbit CD2", "$rootUri/The Hobbit/The Hobbit CD2", children = listOf(
                                file("02.mp3", cd2Track2),
                                file("01.mp3", cd2Track1),
                            )
                        ),
                        dir(
                            "The Hobbit CD1", "$rootUri/The Hobbit/The Hobbit CD1", children = listOf(
                                file("02.mp3", cd1Track2),
                                file("01.mp3", cd1Track1),
                            )
                        ),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(1, result.books.size)
        val book = result.books.single()
        assertEquals("The Hobbit", book.title)
        assertEquals(
            listOf(cd1Track1, cd1Track2, cd2Track1, cd2Track2),
            book.tracks.map { it.uri },
        )
    }

    @Test
    fun `mixed separator disc folders sort by terminal number`() {
        val disc2Track = "$rootUri/The Hobbit/The Hobbit-CD2/track.mp3"
        val disc10Track = "$rootUri/The Hobbit/The Hobbit CD10/track.mp3"
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "The Hobbit", "$rootUri/The Hobbit", children = listOf(
                        dir(
                            "The Hobbit CD10", "$rootUri/The Hobbit/The Hobbit CD10", children = listOf(
                                file("track.mp3", disc10Track),
                            )
                        ),
                        dir(
                            "The Hobbit-CD2", "$rootUri/The Hobbit/The Hobbit-CD2", children = listOf(
                                file("track.mp3", disc2Track),
                            )
                        ),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(listOf(disc2Track, disc10Track), result.books.single().tracks.map { it.uri })
    }

    @Test
    fun `keyword inside a word does not make numbered siblings merge`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Collection", "$rootUri/Collection", children = listOf(
                        dir(
                            "Counterpart 1", "$rootUri/Collection/Counterpart 1", children = listOf(
                                file("one.mp3", "$rootUri/Collection/Counterpart 1/one.mp3"),
                            )
                        ),
                        dir(
                            "Counterpart 2", "$rootUri/Collection/Counterpart 2", children = listOf(
                                file("two.mp3", "$rootUri/Collection/Counterpart 2/two.mp3"),
                            )
                        ),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(setOf("Counterpart 1", "Counterpart 2"), result.books.map { it.title }.toSet())
        assertFalse(result.books.any { it.title == "Collection" })
    }

    @Test(timeout = 250L)
    fun `separator only folder name completes without merging`() {
        val separatorOnlyName = "-".repeat(10_000)
        val folderUri = "$rootUri/$separatorOnlyName"
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Collection", "$rootUri/Collection", children = listOf(
                        dir(
                            separatorOnlyName, folderUri, children = listOf(
                                file("track.mp3", "$folderUri/track.mp3"),
                            )
                        )
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(listOf(separatorOnlyName), result.books.map { it.title })
        assertFalse(result.books.any { it.title == "Collection" })
    }

    @Test
    fun `different disc folder prefixes stay as separate books`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Book", "$rootUri/Book", children = listOf(
                        dir(
                            "Alpha CD1", "$rootUri/Book/Alpha CD1", children = listOf(
                                file("alpha.mp3", "$rootUri/Book/Alpha CD1/alpha.mp3"),
                            )
                        ),
                        dir(
                            "Beta CD2", "$rootUri/Book/Beta CD2", children = listOf(
                                file("beta.mp3", "$rootUri/Book/Beta CD2/beta.mp3"),
                            )
                        ),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(2, result.books.size)
        assertEquals(setOf("Alpha CD1", "Beta CD2"), result.books.map { it.title }.toSet())
        assertFalse(result.books.any { it.title == "Book" })
    }

    @Test
    fun `chapter folders merge into the parent book in natural chapter order`() {
        val chapter1 = "$rootUri/Book/Chapter 1/ch.mp3"
        val chapter2 = "$rootUri/Book/Chapter 02/ch.mp3"
        val chapter3 = "$rootUri/Book/Chapter 3/ch.mp3"
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Book", "$rootUri/Book", children = listOf(
                        dir("Chapter 3", "$rootUri/Book/Chapter 3", children = listOf(file("ch.mp3", chapter3))),
                        dir("Chapter 02", "$rootUri/Book/Chapter 02", children = listOf(file("ch.mp3", chapter2))),
                        dir("Chapter 1", "$rootUri/Book/Chapter 1", children = listOf(file("ch.mp3", chapter1))),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(1, result.books.size)
        val book = result.books.single()
        assertEquals("Book", book.title)
        assertEquals(listOf(chapter1, chapter2, chapter3), book.tracks.map { it.uri })
    }

    @Test
    fun `bare CD folders still merge into the parent book`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Book", "$rootUri/Book", children = listOf(
                        dir(
                            "CD1", "$rootUri/Book/CD1", children = listOf(
                                file("one.mp3", "$rootUri/Book/CD1/one.mp3"),
                            )
                        ),
                        dir(
                            "CD2", "$rootUri/Book/CD2", children = listOf(
                                file("two.mp3", "$rootUri/Book/CD2/two.mp3"),
                            )
                        ),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(1, result.books.size)
        assertEquals("Book", result.books.single().title)
        assertEquals(2, result.books.single().tracks.size)
    }

    @Test
    fun `numbered folders without a supported keyword stay as separate books`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Series", "$rootUri/Series", children = listOf(
                        dir(
                            "Book 1", "$rootUri/Series/Book 1", children = listOf(
                                file("one.mp3", "$rootUri/Series/Book 1/one.mp3"),
                            )
                        ),
                        dir(
                            "Book 2", "$rootUri/Series/Book 2", children = listOf(
                                file("two.mp3", "$rootUri/Series/Book 2/two.mp3"),
                            )
                        ),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(2, result.books.size)
        assertEquals(setOf("Book 1", "Book 2"), result.books.map { it.title }.toSet())
        assertFalse(result.books.any { it.title == "Series" })
    }

    @Test
    fun `prefixed disc folder with nested audio prevents the parent merge`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "The Hobbit", "$rootUri/The Hobbit", children = listOf(
                        dir(
                            "The Hobbit CD1", "$rootUri/The Hobbit/The Hobbit CD1", children = listOf(
                                file("track1.mp3", "$rootUri/The Hobbit/The Hobbit CD1/track1.mp3"),
                                dir(
                                    "Bonus", "$rootUri/The Hobbit/The Hobbit CD1/Bonus", children = listOf(
                                        file(
                                            "bonus.mp3",
                                            "$rootUri/The Hobbit/The Hobbit CD1/Bonus/bonus.mp3",
                                        ),
                                    )
                                ),
                            )
                        ),
                        dir(
                            "The Hobbit CD2", "$rootUri/The Hobbit/The Hobbit CD2", children = listOf(
                                file("track2.mp3", "$rootUri/The Hobbit/The Hobbit CD2/track2.mp3"),
                            )
                        ),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(
            setOf("The Hobbit CD1", "The Hobbit CD2", "Bonus"),
            result.books.map { it.title }.toSet(),
        )
        assertFalse(result.books.any { it.title == "The Hobbit" })
        assertEquals(3, result.books.sumOf { it.tracks.size })
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
    fun `each folder is listed exactly once per scan`() {
        val cd1 = dir(
            "CD1", "$rootUri/Book/CD1", children = listOf(
                file("track1.mp3", "$rootUri/Book/CD1/track1.mp3"),
            )
        )
        val cd2 = dir(
            "CD2", "$rootUri/Book/CD2", children = listOf(
                file("track2.mp3", "$rootUri/Book/CD2/track2.mp3"),
            )
        )
        val book = dir("Book", "$rootUri/Book", children = listOf(cd1, cd2))
        val root = dir(null, rootUri, children = listOf(book))

        engine().scan(root, rootUri)

        for (folder in listOf(root, book, cd1, cd2)) {
            assertEquals("${folder.name} was listed more than once", 1, folder.childrenCallCount)
        }
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
    fun `deep other sibling exhausts lookahead and prevents a parent merge`() {
        fun emptyChain(level: Int): ScanNode {
            if (level == 30) {
                return dir("Nested$level", "$rootUri/Book/Other/Nested$level")
            }
            return dir(
                "Nested$level", "$rootUri/Book/Other/Nested$level", children = listOf(
                    emptyChain(level + 1)
                )
            )
        }

        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Book", "$rootUri/Book", children = listOf(
                        dir(
                            "CD1", "$rootUri/Book/CD1", children = listOf(
                                file("track.mp3", "$rootUri/Book/CD1/track.mp3"),
                            )
                        ),
                        dir(
                            "Other", "$rootUri/Book/Other", children = listOf(
                                emptyChain(1)
                            )
                        ),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(listOf("CD1"), result.books.map { it.title })
        assertFalse(result.books.any { it.title == "Book" })
    }

    @Test
    fun `audio nested below a disc folder prevents merging without losing tracks`() {
        val root = dir(
            null, rootUri, children = listOf(
                dir(
                    "Book", "$rootUri/Book", children = listOf(
                        dir(
                            "CD1", "$rootUri/Book/CD1", children = listOf(
                                file("track1.mp3", "$rootUri/Book/CD1/track1.mp3"),
                                dir(
                                    "Bonus", "$rootUri/Book/CD1/Bonus", children = listOf(
                                        file("bonus.mp3", "$rootUri/Book/CD1/Bonus/bonus.mp3"),
                                    )
                                ),
                            )
                        ),
                        dir(
                            "CD2", "$rootUri/Book/CD2", children = listOf(
                                file("track2.mp3", "$rootUri/Book/CD2/track2.mp3"),
                            )
                        ),
                    )
                )
            )
        )

        val result = engine().scan(root, rootUri)

        assertEquals(setOf("CD1", "CD2", "Bonus"), result.books.map { it.title }.toSet())
        assertFalse(result.books.any { it.title == "Book" })
        assertEquals(3, result.books.sumOf { it.tracks.size })
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

    @Test
    fun `disc merge falls back to capped recursion when subfolders exceed remaining room`() {
        val discFolders = (1..LocalScanEngine.MAX_FOLDERS_SCANNED).map { disc ->
            dir(
                "CD$disc", "$rootUri/Book/CD$disc", children = listOf(
                    file("track.mp3", "$rootUri/Book/CD$disc/track.mp3"),
                )
            )
        }
        val root = dir(
            null, rootUri, children = listOf(
                dir("Book", "$rootUri/Book", children = discFolders)
            )
        )

        val result = engine().scan(root, rootUri)

        assertFalse(result.books.any { it.title == "Book" })
        assertTrue(result.errorMessages.any { it.contains("${LocalScanEngine.MAX_FOLDERS_SCANNED}") })
        assertTrue(result.foldersScanned <= LocalScanEngine.MAX_FOLDERS_SCANNED)
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
