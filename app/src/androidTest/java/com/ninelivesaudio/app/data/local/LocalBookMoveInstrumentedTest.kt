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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/** Issue #46: a local book moved to another folder takes its bookmarks and listening history along. */
@RunWith(AndroidJUnit4::class)
class LocalBookMoveInstrumentedTest {

    private val app = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as NineLivesApp
    private lateinit var database: AppDatabase
    private lateinit var kit: LocalLibraryTestKit

    private val tracks = listOf(Triple("01.mp3", 1_000_000L, null as String?), Triple("02.mp3", 2_000_000L, null as String?))
    private lateinit var a: AudioBook
    private lateinit var a2: AudioBook

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        kit = LocalLibraryTestKit(app, database)
        kit.seedLibrary()
        a = kit.scanned("A", "Dracula", "Dracula", tracks)
        a2 = kit.scanned("A2", "Moved/Dracula", "Dracula", tracks)
        kit.repository.importLocalBooks(LIB, listOf(a))
    }

    @After
    fun tearDown() = database.close()

    /** The way LocalScanImporter.importAndReconcile runs a scan in which A has moved to A2. */
    private suspend fun scanWithTheMove() {
        kit.repository.importLocalBooksCarryingMoves(LIB, listOf(a2), existingIds = listOf("A"), seenIds = listOf("A2"))
        kit.repository.removeMissingLocalBooks(LIB, listOf("A2"))
    }

    private suspend fun bookmarks(id: String) =
        database.localBookmarkDao().getByAudioBookId(id).map { it.time to it.title }

    @Test
    fun aMovedBookTakesItsBookmarksAndListeningHistoryWithIt() = runBlocking {
        kit.progress("A", 300.0)
        kit.bookmark("A", 60.0, "Lucy")
        kit.bookmark("A", 240.0, "Castle")
        kit.session("A", 180.0)
        kit.session("A", 180.0, startedAt = System.currentTimeMillis() - 3_600_000L)

        scanWithTheMove()

        assertEquals(listOf(60.0 to "Lucy", 240.0 to "Castle"), bookmarks("A2"))
        assertTrue(bookmarks("A").isEmpty())
        assertEquals(2, database.localListeningSessionDao().getByAudioBookId("A2").size)
        assertTrue(database.localListeningSessionDao().getByAudioBookId("A").isEmpty())
        assertEquals(300.0, database.playbackProgressDao().getByAudioBookId("A2")!!.positionSeconds, 0.0)
    }

    @Test
    fun aMovedBookWithNoSavedPlaceStillTakesItsBookmarks() = runBlocking {
        kit.bookmark("A", 60.0, "Lucy")
        kit.bookmark("A", 240.0, "Castle")
        kit.session("A", 180.0)
        kit.session("A", 180.0, startedAt = System.currentTimeMillis() - 3_600_000L)

        scanWithTheMove()

        assertEquals(listOf(60.0 to "Lucy", 240.0 to "Castle"), bookmarks("A2"))
        assertEquals(2, database.localListeningSessionDao().getByAudioBookId("A2").size)
    }

    @Test
    fun aBookmarkAtTheSameSecondOnTheNewRowStaysOnTheOldRow() = runBlocking {
        kit.bookmark("A", 60.0, "Lucy")
        kit.bookmark("A", 240.0, "Castle")
        // The new row already holds a bookmark at 60.0 (the unique key is book + time).
        kit.repository.importLocalBooks(LIB, listOf(a2))
        kit.bookmark("A2", 60.0, "Mine")

        scanWithTheMove()

        assertEquals(listOf(60.0 to "Mine", 240.0 to "Castle"), bookmarks("A2"))
        assertEquals(listOf(60.0 to "Lucy"), bookmarks("A"))
        assertEquals(3, bookmarks("A").size + bookmarks("A2").size)
    }

    @Test
    fun dossierTotalsAreTheSameBeforeAndAfterAMove() = runBlocking {
        for (includeArchived in listOf(false, true)) {
            // A fresh start per setting: A live with two sessions, then the move.
            database.clearAllTables()
            kit.seedLibrary()
            kit.repository.importLocalBooks(LIB, listOf(a))
            kit.session("A", 180.0)
            kit.session("A", 180.0, startedAt = System.currentTimeMillis() - 3_600_000L)

            kit.withLocalMode(includeArchivedInStats = includeArchived) {
                val before = kit.dossier()
                scanWithTheMove()
                val after = kit.dossier()

                assertEquals("total, includeArchived=$includeArchived", before.total, after.total)
                assertEquals(before.sessions, after.sessions)
                assertEquals(before.books, after.books)
                assertEquals(listOf("A2" to 360.seconds), after.perBook)
            }
        }
    }
}
