package com.ninelivesaudio.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.NineLivesApp
import com.ninelivesaudio.app.data.local.LocalLibraryTestKit.Companion.LIB
import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #47: an archived local book comes back for the same book only. A
 * different book in its folder starts fresh, and the old book keeps its place
 * and history in the Archive on a row of its own.
 */
@RunWith(AndroidJUnit4::class)
class LocalBookRevivalInstrumentedTest {

    private val app = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as NineLivesApp
    private lateinit var database: AppDatabase
    private lateinit var kit: LocalLibraryTestKit

    private val archivedAt = System.currentTimeMillis() - 5 * 86_400_000L
    private val oldFiles = listOf(Triple("01.mp3", 1_000L, null as String?))
    private val newFiles = listOf(Triple("01.mp3", 2_000L, null as String?), Triple("02.mp3", 3_000L, null as String?))
    private val asideId get() = "A~$archivedAt"
    private lateinit var different: AudioBook

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        kit = LocalLibraryTestKit(app, database)
        kit.seedLibrary()
        kit.repository.importLocalBooks(LIB, listOf(kit.scanned("A", "Swap/Old Book", "Old Book", oldFiles)))
        database.audioBookDao().updateLocalPosition("A", 1200.0, 0.3, 0)
        kit.progress("A", 1200.0)
        kit.bookmark("A", 600.0, "Old mark")
        kit.session("A", 180.0)
        kit.session("A", 180.0, startedAt = System.currentTimeMillis() - 3_600_000L)
        database.audioBookDao().archiveByIds(listOf("A"), archivedAt)
        different = kit.scanned("A", "Swap/Old Book", "New Book", newFiles)
    }

    @After
    fun tearDown() = database.close()

    private suspend fun scan(vararg books: AudioBook, existing: List<String> = emptyList()) {
        kit.repository.importLocalBooksCarryingMoves(LIB, books.toList(), existing, books.map { it.id })
        kit.repository.removeMissingLocalBooks(LIB, books.map { it.id })
    }

    @Test
    fun theSameBookReturningToItsFolderRevivesWithItsPlace() = runBlocking {
        scan(kit.scanned("A", "Swap/Old Book", "Old Book", oldFiles))

        val row = database.audioBookDao().getById("A")!!
        assertNull(row.archivedAt)
        assertEquals(1200.0, row.currentTimeSeconds, 0.0)
        assertEquals(1200.0, database.playbackProgressDao().getByAudioBookId("A")!!.positionSeconds, 0.0)
        assertEquals(1, database.localBookmarkDao().getByAudioBookId("A").size)
        assertTrue(database.audioBookDao().getLocalIdsByLibrary(LIB).none { it.startsWith("A~") })
    }

    @Test
    fun aDifferentBookAtAnArchivedBooksFolderStartsFresh() = runBlocking {
        scan(different)

        val row = database.audioBookDao().getById("A")!!
        assertEquals("New Book", row.title)
        assertNull(row.archivedAt)
        assertEquals(0.0, row.currentTimeSeconds, 0.0)
        assertEquals(0.0, row.progress, 0.0)
        assertEquals(0, row.isFinished)
        assertNull(database.playbackProgressDao().getByAudioBookId("A"))
        assertTrue(database.localBookmarkDao().getByAudioBookId("A").isEmpty())
        assertTrue(database.localListeningSessionDao().getByAudioBookId("A").isEmpty())
    }

    @Test
    fun theOldBooksPlaceAndHistoryStayInTheArchive() = runBlocking {
        scan(different)

        val aside = database.audioBookDao().getById(asideId)!!
        assertEquals(archivedAt, aside.archivedAt)
        assertEquals("Old Book", aside.title)
        assertEquals(1200.0, aside.currentTimeSeconds, 0.0)
        assertNull(aside.coverPath)
        assertNull(aside.localCoverPath)
        assertEquals(1200.0, database.playbackProgressDao().getByAudioBookId(asideId)!!.positionSeconds, 0.0)
        assertEquals(1, database.localBookmarkDao().getByAudioBookId(asideId).size)
        assertEquals(2, database.localListeningSessionDao().getByAudioBookId(asideId).size)
        assertTrue(asideId in database.audioBookDao().getArchivedLocalIdsByLibrary(LIB))
    }

    @Test
    fun aSecondRescanSetsNothingAsideAgain() = runBlocking {
        scan(different)
        scan(different, existing = listOf("A"))

        val asides = database.audioBookDao().getLocalIdsByLibrary(LIB).filter { it.startsWith("A~") }
        assertEquals(listOf(asideId), asides)
        assertEquals(archivedAt, database.audioBookDao().getById(asideId)!!.archivedAt)
        assertEquals("New Book", database.audioBookDao().getById("A")!!.title)
        assertNull(database.audioBookDao().getById("A")!!.archivedAt)
    }

    @Test
    fun aBookMovedOntoAnArchivedBooksFolderBringsItsOwnPlace() = runBlocking {
        val b = kit.scanned("B", "Elsewhere/Old Book", "New Book", newFiles)
        kit.repository.importLocalBooks(LIB, listOf(b))
        kit.progress("B", 500.0)
        kit.bookmark("B", 100.0, "B mark")

        // B is gone and A arrives with B's files.
        scan(different, existing = listOf("B"))

        assertEquals(500.0, database.playbackProgressDao().getByAudioBookId("A")!!.positionSeconds, 0.0)
        assertEquals(listOf(100.0 to "B mark"), database.localBookmarkDao().getByAudioBookId("A").map { it.time to it.title })
        assertEquals(1, database.localBookmarkDao().getByAudioBookId(asideId).size)
        assertEquals(1200.0, database.playbackProgressDao().getByAudioBookId(asideId)!!.positionSeconds, 0.0)
        assertNotNull(database.audioBookDao().getById("B")!!.archivedAt)
    }

    @Test
    fun theDossierStillCountsTheOldBooksHistoryAsArchived() = runBlocking {
        kit.withLocalMode(includeArchivedInStats = false) {
            val before = kit.dossier()
            scan(different)
            val after = kit.dossier()

            assertEquals(before.total, after.total)
            assertEquals(before.sessions, after.sessions)
        }
    }
}
