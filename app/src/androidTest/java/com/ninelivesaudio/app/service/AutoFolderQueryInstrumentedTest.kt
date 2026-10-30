package com.ninelivesaudio.app.service

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.data.local.AUTO_TRIM_CHARS
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SQL behind Android Auto's letter folders. SQL cannot call Kotlin, so
 * it files titles by its own copy of the Library rail's rule, over the
 * folded title the SearchText column stores. These pin the two together and
 * walk the real queries to prove every book is reachable exactly once.
 */
@RunWith(AndroidJUnit4::class)
class AutoFolderQueryInstrumentedTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sqlLettersMatchTheKotlinTitleKey() = runBlocking {
        val titles = listOf(
            "dune", "Dune", "The Hobbit", "A Wizard of Earthsea", "An Echo", "  Mort", "Émile", "Östen",
            "ñandú", "Øystein", "Łódź", "Æsir", "1984", "'Salem's Lot", "[REC]", "日本語", "Привет",
            "", "   ", " Mort", "Þorn",
            "T", "T-Rex", "T 2", "Tō", "📚 Books", "A📚", "ßtraße", " Em space", "　Ideographic",
            "T Rex", "Zz", "zebra", "q", "X\tY",
        )
        val dao = database.audioBookDao()
        dao.upsertAll(titles.mapIndexed { i, title -> book(id = "b$i", title = title, libraryId = "p$i") })

        titles.forEachIndexed { i, title ->
            val letters = dao.countAutoLetters("p$i", 0, 0, AUTO_TRIM_CHARS)
            assertEquals("letter of '$title'", autoTitleKey(title, 1), letters.single().bucket)
            assertEquals(1, letters.single().count)
            val letter = letters.single().bucket
            if (letter != "#") {
                val pairs = dao.countAutoPairs("p$i", 0, 0, AUTO_TRIM_CHARS, letter)
                assertEquals("pair of '$title'", autoTitleKey(title, 2), pairs.single().bucket)
            }
        }
    }

    @Test
    fun blankAndNbspTitlesNeverReadTheAuthor() = runBlocking {
        val dao = database.audioBookDao()
        dao.upsertAll(
            listOf(
                book(id = "blank", title = "", libraryId = "p1").copy(author = "Zed"),
                book(id = "spaces", title = "   ", libraryId = "p2").copy(author = "Zed"),
                book(id = "nbsp", title = " Mort", libraryId = "p3").copy(author = "Zed"),
            ),
        )
        assertEquals("#", dao.countAutoLetters("p1", 0, 0, AUTO_TRIM_CHARS).single().bucket)
        assertEquals("#", dao.countAutoLetters("p2", 0, 0, AUTO_TRIM_CHARS).single().bucket)
        assertEquals("M", dao.countAutoLetters("p3", 0, 0, AUTO_TRIM_CHARS).single().bucket)
        assertEquals("MO", dao.countAutoPairs("p3", 0, 0, AUTO_TRIM_CHARS, "M").single().bucket)
        assertEquals(
            listOf("blank"),
            dao.getAutoBucketPage("p1", 0, 0, AUTO_TRIM_CHARS, "#", null, 10, 0).map { it.id },
        )
    }

    @Test
    fun everyBookIsReachableOnceThroughTheRealQueries() = runBlocking {
        val dao = database.audioBookDao()
        val live = seedBigLibrary()
        val source = RoomAutoFolderSource(dao, LIBRARY, isLocal = false, branch = AutoBranch.LIBRARY)

        val reached = walk(AutoFolderId(AutoBranch.LIBRARY, null), source)

        assertEquals(live.size, reached.size)
        assertEquals(live.map { it.id }.sorted(), reached.sorted())
    }

    @Test
    fun downloadedBranchWalksOnlyDownloadedBooks() = runBlocking {
        val dao = database.audioBookDao()
        val live = seedBigLibrary()
        val downloaded = live.filter { it.isDownloaded == 1 }
        assertTrue(downloaded.size > AUTO_FOLDER_CAP)
        val source = RoomAutoFolderSource(dao, LIBRARY, isLocal = false, branch = AutoBranch.DOWNLOADED)

        val reached = walk(AutoFolderId(AutoBranch.DOWNLOADED, null), source)

        assertEquals(downloaded.map { it.id }.sorted(), reached.sorted())
    }

    @Test
    fun bucketPagesKeepTheFlatOrder() = runBlocking {
        val dao = database.audioBookDao()
        seedBigLibrary()
        val flat = mutableListOf<Pair<String, String>>()
        var page = 0
        while (true) {
            val rows = dao.getAutoBrowsePage(LIBRARY, 0, 500, page++ * 500)
            if (rows.isEmpty()) break
            flat += rows.map { it.id to it.title }
        }
        val flatT = flat.filter { autoTitleKey(it.second, 1) == "T" }.map { it.first }

        val bucketT = mutableListOf<String>()
        page = 0
        while (true) {
            val rows = dao.getAutoBucketPage(LIBRARY, 0, 0, AUTO_TRIM_CHARS, "T", null, 97, page++ * 97)
            if (rows.isEmpty()) break
            bucketT += rows.map { it.id }
        }

        assertTrue(flatT.size > 1_000)
        assertEquals(flatT, bucketT)
    }

    @Test
    fun partBoundaryTitlesMatchThePages() = runBlocking {
        val dao = database.audioBookDao()
        seedBigLibrary()
        val source = RoomAutoFolderSource(dao, LIBRARY, isLocal = false, branch = AutoBranch.LIBRARY)
        // "TH" holds the 700 "The ..." titles plus a few "Th..." ones: two parts.
        val n = source.pairCounts("T").getValue("TH")
        val expected = (0 until n step 100).map { source.page("TH", 1, it).single().title }

        assertEquals(expected, dao.everyNthAutoTitle(LIBRARY, 0, 0, AUTO_TRIM_CHARS, "T", "H", 100, 0, n))
        assertEquals(expected.drop(2).take(3), dao.everyNthAutoTitle(LIBRARY, 0, 0, AUTO_TRIM_CHARS, "T", "H", 100, 200, 500))
        assertEquals(expected, source.everyNthTitle("TH", 100, 0, n))
    }

    /** Walks like Android Auto (page 0, Int.MAX_VALUE), checking no list is over the cap. */
    private suspend fun walk(id: AutoFolderId, source: AutoFolderSource, depth: Int = 0): List<String> {
        val kids = autoChildren(id, source, 0, Int.MAX_VALUE)
        assertTrue("${id.mediaId} lists ${kids.size}", kids.size <= AUTO_FOLDER_CAP)
        assertTrue("${id.mediaId} is too deep", depth <= 4)
        assertEquals(id.mediaId, kids.size, autoChildCount(id, source))
        return kids.flatMap { child ->
            when (child) {
                is AutoBrowseChild.Book -> listOf(child.row.id)
                is AutoBrowseChild.Folder -> walk(child.id, source, depth + 1)
            }
        }
    }

    /**
     * 3,000 live books: 1,200 under T (700 of them "The ..."), 600 in other
     * alphabets, the rest spread, plus archived, other library and local rows
     * that must never appear. Every second book is downloaded.
     */
    private suspend fun seedBigLibrary(): List<AudioBookEntity> {
        val words = listOf("Apple", "Bridge", "Cinder", "Dune", "Ember", "Frost", "Garden", "Harbor", "Iris",
            "Jade", "Kettle", "Lantern", "Meadow", "Night", "Ocean", "Pine", "Quill", "River", "Stone",
            "Umber", "Vale", "Willow", "Xenon", "Yarrow", "Zephyr", "1984", "'Salem")
        val titles = List(700) { "The Story ${it.toString().padStart(4, '0')}" } +
            List(500) { "T${('a' + it % 26)}${it.toString().padStart(4, '0')}" } +
            List(400) { "Привет $it" } + List(200) { "日本語 $it" } +
            List(1_200) { "${words[it % words.size]} ${it / words.size}" }
        val live = titles.mapIndexed { i, title -> book(id = "live$i", title = title, isDownloaded = i % 2) }
        val noise = listOf(
            book(id = "archived", title = "The Archived", archivedAt = 1L, isDownloaded = 1),
            book(id = "other-library", title = "The Other", libraryId = "other", isDownloaded = 1),
            book(id = "local", title = "The Local", isLocal = 1, isDownloaded = 1),
        )
        database.audioBookDao().upsertAll(live + noise)
        return live
    }

    private fun book(
        id: String,
        title: String,
        libraryId: String = LIBRARY,
        isLocal: Int = 0,
        isDownloaded: Int = 0,
        archivedAt: Long? = null,
    ) = AudioBookEntity(
        id = id,
        libraryId = libraryId,
        isLocal = isLocal,
        title = title,
        author = "Author",
        isDownloaded = isDownloaded,
        archivedAt = archivedAt,
    )

    private companion object {
        const val LIBRARY = "lib"
    }
}
