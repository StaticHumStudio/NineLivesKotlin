package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.local.entity.AutoBrowseRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The Android Auto tree for Library and Downloaded: a flat list up to 500
 * books, then letter folders, two letter folders and numbered parts, so
 * every book is reachable by browsing at any size and no list Auto reads is
 * ever longer than 500 (Auto shows only the first 500 of a list).
 */
class AutoBrowseFoldersTest {

    /** In memory stand in for the SQL source, sorted and keyed the way SQL does it. */
    private class FakeAutoFolderSource(books: List<AutoBrowseRow>) : AutoFolderSource {
        val sorted = books.sortedWith(compareBy<AutoBrowseRow>({ it.title.lowercase() }, { it.id }))
        val limits = mutableListOf<Int>()

        override suspend fun countAll(): Int = sorted.size

        override suspend fun letterCounts(): Map<String, Int> =
            sorted.groupingBy { autoTitleKey(it.title, 1) }.eachCount()

        override suspend fun pairCounts(letter: String): Map<String, Int> =
            sorted.filter { autoTitleKey(it.title, 1) == letter }
                .groupingBy { autoTitleKey(it.title, 2) }.eachCount()

        override suspend fun page(key: String?, limit: Int, offset: Int): List<AutoBrowseRow> {
            limits += limit
            pageReads++
            val rows = if (key == null) sorted else sorted.filter { autoTitleKey(it.title, key.length) == key }
            return rows.drop(offset).take(limit)
        }

        var pageReads = 0
        var titleReads = 0

        override suspend fun everyNthTitle(key: String, every: Int, fromRow: Int, toRow: Int): List<String> {
            titleReads++
            val rows = sorted.filter { autoTitleKey(it.title, key.length) == key }
            return (fromRow until minOf(toRow, rows.size)).filter { it % every == 0 }.map { rows[it].title }
        }
    }

    private val root = AutoFolderId(AutoBranch.LIBRARY, null)

    private fun book(id: String, title: String) = AutoBrowseRow(id = id, title = title)

    private fun spread(count: Int, prefix: String = "b"): List<AutoBrowseRow> {
        val words = listOf("Apple", "Bridge", "Cinder", "Dune", "Ember", "Frost", "Garden", "Harbor", "Iris",
            "Jade", "Kettle", "Lantern", "Meadow", "Night", "Ocean", "Pine", "Quill", "River", "Stone",
            "Tower", "Umber", "Vale", "Willow", "Xenon", "Yarrow", "Zephyr")
        return List(count) { i -> book("$prefix$i", "${words[i % words.size]} ${i / words.size}") }
    }

    private suspend fun children(id: AutoFolderId, source: AutoFolderSource, page: Int = 0, pageSize: Int = Int.MAX_VALUE) =
        autoChildren(id, source, page, pageSize, Locale.US)

    /** Walks like Android Auto: page 0, Int.MAX_VALUE. Returns book ids, checking list sizes and depth on the way. */
    private suspend fun walk(
        id: AutoFolderId,
        source: AutoFolderSource,
        depth: Int = 0,
        visit: suspend (AutoFolderId, List<AutoBrowseChild>) -> Unit = { _, _ -> },
    ): List<String> {
        val kids = children(id, source)
        assertTrue("${id.mediaId} lists ${kids.size}", kids.size <= AUTO_FOLDER_CAP)
        assertTrue("${id.mediaId} is too deep", depth <= 4)
        visit(id, kids)
        return kids.flatMap { child ->
            when (child) {
                is AutoBrowseChild.Book -> listOf(child.row.id)
                is AutoBrowseChild.Folder -> walk(child.id, source, depth + 1, visit)
            }
        }
    }

    private suspend fun assertEveryBookOnce(books: List<AutoBrowseRow>) {
        val source = FakeAutoFolderSource(books)
        val reached = walk(root, source)
        assertEquals(books.size, reached.size)
        assertEquals(books.map { it.id }.sorted(), reached.sorted())
    }

    private fun stress(): List<AutoBrowseRow> =
        List(2_000) { book("the$it", "The Story ${it.toString().padStart(4, '0')}") } +
            List(900) { book("ru$it", "Привет ${it.toString().padStart(4, '0')}") } +
            List(1_200) { book("same$it", "Same Title") } +
            spread(4_000, prefix = "s")

    @Test
    fun `40 books stay one flat list`() = runBlocking {
        val books = spread(40)
        val kids = children(root, FakeAutoFolderSource(books))
        assertEquals(40, kids.size)
        assertTrue(kids.all { it is AutoBrowseChild.Book })
        val expected = books.sortedWith(compareBy({ it.title.lowercase() }, { it.id })).map { it.id }
        assertEquals(expected, kids.map { (it as AutoBrowseChild.Book).row.id })
    }

    @Test
    fun `500 books stay flat and 501 become letters`() = runBlocking {
        val flat = children(root, FakeAutoFolderSource(spread(500)))
        assertEquals(500, flat.size)
        assertTrue(flat.all { it is AutoBrowseChild.Book })
        val folders = children(root, FakeAutoFolderSource(spread(501)))
        assertTrue(folders.isNotEmpty())
        assertTrue(folders.all { it is AutoBrowseChild.Folder })
    }

    @Test
    fun `501 books become letter folders, number sign first`() = runBlocking {
        val books = spread(495) + List(6) { book("n$it", "${1900 + it}") }
        val kids = children(root, FakeAutoFolderSource(books)).map { it as AutoBrowseChild.Folder }
        val expectedLetters = listOf("#") + ('A'..'Z').map { it.toString() }
        assertEquals(
            expectedLetters.map { "library/$it" },
            kids.map { it.id.mediaId },
        )
        assertEquals("#", kids[0].title)
        assertEquals("Numbers and symbols, 6 books", kids[0].subtitle)
        assertEquals("A", kids[1].title)
        assertEquals("20 books", kids[1].subtitle)
    }

    @Test
    fun `empty letters get no folder`() = runBlocking {
        val books = List(300) { book("a$it", "Alpha $it") } + List(300) { book("c$it", "Charlie $it") }
        val kids = children(root, FakeAutoFolderSource(books)).map { (it as AutoBrowseChild.Folder).id.mediaId }
        assertEquals(listOf("library/A", "library/C"), kids)
    }

    @Test
    fun `a letter with 1,200 books splits into two letter folders`() = runBlocking {
        val seconds = ('a'..'z').toList()
        val tBooks = List(1_200) { book("t$it", "T${seconds[it % 26]}${it.toString().padStart(4, '0')}") }
        val source = FakeAutoFolderSource(tBooks + spread(100))
        val kids = children(AutoFolderId(AutoBranch.LIBRARY, "T"), source).map { it as AutoBrowseChild.Folder }
        assertTrue(kids.all { it.id.key!!.length == 2 && it.id.key!!.startsWith("T") && it.id.part == null })
        val counts = kids.map { autoChildCount(it.id, source) }
        assertTrue(counts.all { it <= AUTO_FOLDER_CAP })
        assertEquals(1_200 + 4, counts.sum()) // the spread adds "Tower 0".."Tower 3"
        assertEquals("Ta", kids.first { it.id.key == "TA" }.title)
    }

    @Test
    fun `the number sign pair comes first inside a letter`() = runBlocking {
        val books = List(400) { book("t$it", "T-$it") } + List(400) { book("u$it", "Ta $it") }
        val source = FakeAutoFolderSource(books)
        val kids = children(AutoFolderId(AutoBranch.LIBRARY, "T"), source).map { it as AutoBrowseChild.Folder }
        assertEquals(listOf("library/T#", "library/TA"), kids.map { it.id.mediaId })
        assertEquals("T#", kids[0].title)
    }

    @Test
    fun `every book is reachable exactly once`() = runBlocking {
        assertEveryBookOnce(spread(40))
        assertEveryBookOnce(spread(501))
        assertEveryBookOnce(List(1_200) { book("t$it", "T${('a' + it % 26)} $it") } + spread(300))
        assertEveryBookOnce(stress())
    }

    @Test
    fun `a library heavy with The titles reaches every book`() = runBlocking {
        val books = List(2_000) { book("the$it", "The Story $it") } + spread(600)
        val source = FakeAutoFolderSource(books)
        val th = children(AutoFolderId(AutoBranch.LIBRARY, "TH"), source).map { it as AutoBrowseChild.Folder }
        assertEquals((1..4).map { "library/TH/$it" }, th.map { it.id.mediaId })
        assertEquals("Th, part 2", th[1].title)
        assertTrue(th[1].subtitle!!.startsWith("From The Story"))
        assertEveryBookOnce(books)
    }

    @Test
    fun `a non Latin library reaches every book through number sign parts`() = runBlocking {
        val books = List(1_700) { book("ru$it", "Привет $it") } + List(300) { book("ja$it", "日本語 $it") }
        val source = FakeAutoFolderSource(books)
        assertEquals(listOf("library/#"), children(root, source).map { (it as AutoBrowseChild.Folder).id.mediaId })
        val parts = children(AutoFolderId(AutoBranch.LIBRARY, "#"), source).map { it as AutoBrowseChild.Folder }
        assertEquals((1..4).map { "library/#/$it" }, parts.map { it.id.mediaId })
        assertEquals("#, part 1", parts[0].title)
        assertEveryBookOnce(books)
    }

    @Test
    fun `identical titles split into parts without losing a book`() = runBlocking {
        assertEveryBookOnce(List(1_200) { book("same$it", "Same Title") })
    }

    @Test
    fun `paging inside a folder respects page and pageSize`() = runBlocking {
        val books = List(120) { book("m${it.toString().padStart(3, '0')}", "Moon ${it.toString().padStart(3, '0')}") } +
            spread(500, prefix = "z").filter { autoTitleKey(it.title, 1) != "M" }
        val source = FakeAutoFolderSource(books)
        val m = AutoFolderId(AutoBranch.LIBRARY, "M")
        val all = children(m, source).map { (it as AutoBrowseChild.Book).row.id }
        assertEquals(120, all.size)
        assertEquals(all.subList(0, 50), children(m, source, 0, 50).map { (it as AutoBrowseChild.Book).row.id })
        assertEquals(all.subList(50, 100), children(m, source, 1, 50).map { (it as AutoBrowseChild.Book).row.id })
        assertEquals(all.subList(100, 120), children(m, source, 2, 50).map { (it as AutoBrowseChild.Book).row.id })
        assertTrue(children(m, source, 3, 50).isEmpty())
        assertTrue(children(m, source, 1, 1_000).isEmpty())

        val letters = children(root, FakeAutoFolderSource(spread(26 * 20) + List(5) { book("n$it", "$it") }))
        assertEquals(27, letters.size)
        assertEquals(letters.subList(10, 20), children(root, FakeAutoFolderSource(spread(26 * 20) + List(5) { book("n$it", "$it") }), 1, 10))
    }

    @Test
    fun `paging inside a part never crosses into the next part`() = runBlocking {
        val source = FakeAutoFolderSource(List(1_200) { book("th$it", "The ${it.toString().padStart(4, '0')}") })
        val part1 = AutoFolderId(AutoBranch.LIBRARY, "TH", part = 1)
        val rows = children(part1, source, 4, 120).map { (it as AutoBrowseChild.Book).row.title }
        assertEquals((480 until 500).map { "The ${it.toString().padStart(4, '0')}" }, rows)
        assertTrue(children(part1, source, 5, 120).isEmpty())
    }

    @Test
    fun `nonsense paging reads nothing`() = runBlocking {
        val source = FakeAutoFolderSource(spread(1_200))
        for (id in listOf(root, AutoFolderId(AutoBranch.LIBRARY, "A"), AutoFolderId(AutoBranch.LIBRARY, "AP", part = 1))) {
            assertTrue(children(id, source, -1, 50).isEmpty())
            assertTrue(children(id, source, 0, 0).isEmpty())
            assertTrue(children(id, source, Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        }
        assertTrue(children(AutoFolderId(AutoBranch.LIBRARY, "A"), FakeAutoFolderSource(spread(40)), -1, 50).isEmpty())
    }

    @Test
    fun `the leaf window keeps the old paging rules`() {
        assertEquals(AutoWindow(150, 50), autoLeafWindow(0, 1_000, 3, 50))
        assertEquals(AutoWindow(0, 500), autoLeafWindow(0, 1_000, 0, Int.MAX_VALUE))
        assertNull(autoLeafWindow(0, 1_000, 1, 1_000))
        assertEquals(AutoWindow(0, 50), autoLeafWindow(0, 1_000, 0, 50))
        assertNull(autoLeafWindow(0, 1_000, -1, 50))
        assertNull(autoLeafWindow(0, 1_000, 0, 0))
        assertNull(autoLeafWindow(0, 1_000, 20, 50))
        assertEquals(AutoWindow(950, 50), autoLeafWindow(0, 1_000, 19, 50))
        assertEquals(AutoWindow(500, 500), autoLeafWindow(500, 1_200, 0, 500))
        assertEquals(AutoWindow(1_000, 200), autoLeafWindow(1_000, 1_200, 0, Int.MAX_VALUE))
        assertNull(autoLeafWindow(0, 0, 0, 50))
        assertNull(autoLeafWindow(0, 1_000, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `no read ever asks for more than 500 rows`() = runBlocking {
        val source = FakeAutoFolderSource(stress())
        walk(root, source)
        assertTrue(source.limits.isNotEmpty())
        assertTrue("max ${source.limits.max()}", source.limits.max() <= AUTO_FOLDER_CAP)
    }

    @Test
    fun `a stale part id returns nothing`() = runBlocking {
        val source = FakeAutoFolderSource(List(600) { book("th$it", "The $it") })
        assertTrue(children(AutoFolderId(AutoBranch.LIBRARY, "TH", part = 3), source).isEmpty())
        assertEquals(0, autoChildCount(AutoFolderId(AutoBranch.LIBRARY, "TH", part = 3), source))
    }

    @Test
    fun `a folder id from a bigger library returns the current books`() = runBlocking {
        val books = spread(40)
        val source = FakeAutoFolderSource(books)
        val tIds = books.filter { autoTitleKey(it.title, 1) == "T" }.map { it.id }.sorted()
        val kids = children(AutoFolderId(AutoBranch.LIBRARY, "T"), source)
        assertEquals(tIds, kids.map { (it as AutoBrowseChild.Book).row.id }.sorted())
        assertFalse(tIds.isEmpty())
    }

    @Test
    fun `a pair id resolves even when its letter no longer splits`() = runBlocking {
        val books = List(150) { book("th$it", "The $it") } + List(150) { book("ta$it", "Tale $it") }
        val source = FakeAutoFolderSource(books)
        val kids = children(AutoFolderId(AutoBranch.LIBRARY, "TH"), source)
        assertEquals(150, kids.size)
        assertTrue(kids.all { (it as AutoBrowseChild.Book).row.id.startsWith("th") })
    }

    @Test
    fun `an empty letter returns nothing`() = runBlocking {
        val source = FakeAutoFolderSource(spread(1_000).filter { autoTitleKey(it.title, 1) != "Q" })
        assertTrue(children(AutoFolderId(AutoBranch.LIBRARY, "Q"), source).isEmpty())
        assertEquals(0, autoChildCount(AutoFolderId(AutoBranch.LIBRARY, "Q"), source))
    }

    @Test
    fun `child counts match the children`() = runBlocking {
        val source = FakeAutoFolderSource(stress())
        var nodes = 0
        walk(root, source) { id, kids ->
            nodes++
            assertEquals(id.mediaId, kids.size, autoChildCount(id, source))
        }
        assertTrue(nodes > 30)
    }

    @Test
    fun `counts read in the car's locale`() = runBlocking {
        val books = spread(1_300)
        val kids = autoChildren(root, FakeAutoFolderSource(books), 0, 100, Locale.GERMANY)
        assertTrue(kids.isNotEmpty())
        val a = kids.map { it as AutoBrowseChild.Folder }.first { it.id.key == "A" }
        assertEquals("50 books", a.subtitle)
        val big = autoChildren(root, FakeAutoFolderSource(List(1_204) { book("p$it", "P $it") } + spread(10)), 0, 100, Locale.US)
            .map { it as AutoBrowseChild.Folder }.first { it.id.key == "P" }
        assertEquals("1,204 books", big.subtitle)
        val one = autoChildren(root, FakeAutoFolderSource(List(600) { book("p$it", "P $it") } + book("q", "Quill")), 0, 100, Locale.US)
            .map { it as AutoBrowseChild.Folder }.first { it.id.key == "Q" }
        assertEquals("1 book", one.subtitle)
    }

    @Test
    fun `folder titles`() {
        assertEquals("T", autoFolderTitle(AutoFolderId(AutoBranch.LIBRARY, "T")))
        assertEquals("#", autoFolderTitle(AutoFolderId(AutoBranch.LIBRARY, "#")))
        assertEquals("Th", autoFolderTitle(AutoFolderId(AutoBranch.LIBRARY, "TH")))
        assertEquals("T#", autoFolderTitle(AutoFolderId(AutoBranch.LIBRARY, "T#")))
        assertEquals("Th, part 2", autoFolderTitle(AutoFolderId(AutoBranch.LIBRARY, "TH", part = 2)))
        assertEquals("#, part 3", autoFolderTitle(AutoFolderId(AutoBranch.DOWNLOADED, "#", part = 3)))
        assertEquals("Library", autoFolderTitle(AutoFolderId(AutoBranch.LIBRARY, null)))
        assertEquals("Downloaded", autoFolderTitle(AutoFolderId(AutoBranch.DOWNLOADED, null)))
    }

    @Test
    fun `part subtitles take one read for the whole list`() = runBlocking {
        val source = FakeAutoFolderSource(List(50_000) { book("the$it", "The ${it.toString().padStart(5, '0')}") })
        val parts = children(AutoFolderId(AutoBranch.LIBRARY, "TH"), source).map { it as AutoBrowseChild.Folder }

        assertEquals(100, parts.size)
        assertEquals(0, source.pageReads)
        assertEquals(1, source.titleReads)
        assertEquals("From The 00000", parts[0].subtitle)
        assertEquals("From The 00500", parts[1].subtitle)
        assertEquals("From The 49500", parts[99].subtitle)

        // A page of the part list reads only its own boundaries.
        val page = children(AutoFolderId(AutoBranch.LIBRARY, "TH"), source, page = 2, pageSize = 10)
            .map { it as AutoBrowseChild.Folder }
        assertEquals((21..30).map { "library/TH/$it" }, page.map { it.id.mediaId })
        assertEquals("From The 10000", page[0].subtitle)
    }
}
