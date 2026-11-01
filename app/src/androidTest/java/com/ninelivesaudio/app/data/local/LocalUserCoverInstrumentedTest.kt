package com.ninelivesaudio.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.NineLivesApp
import com.ninelivesaudio.app.data.local.LocalLibraryTestKit.Companion.LIB
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.service.local.fileUriString
import com.ninelivesaudio.app.service.local.isUserPickedCover
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI

/** A cover the user picked survives rescans, moves (#46) and set asides (#47), and undo puts the scan's art back. */
@RunWith(AndroidJUnit4::class)
class LocalUserCoverInstrumentedTest {

    private val app = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as NineLivesApp
    private lateinit var database: AppDatabase
    private lateinit var kit: LocalLibraryTestKit

    private val tracks = listOf(Triple("01.mp3", 1_000_000L, null as String?), Triple("02.mp3", 2_000_000L, null as String?))
    private val bytes = byteArrayOf(1, 2, 3)
    private val pickDir = File(app.filesDir, "user_covers")
    private val scanDir = File(app.filesDir, "local_covers")
    private lateinit var a: AudioBook

    @Before
    fun setUp() = runBlocking {
        pickDir.deleteRecursively()
        scanDir.deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        kit = LocalLibraryTestKit(app, database)
        kit.seedLibrary()
        a = kit.scanned("A", "Dracula", "Dracula", tracks)
        kit.repository.importLocalBooks(LIB, listOf(a))
    }

    @After
    fun tearDown() {
        database.close()
        pickDir.deleteRecursively()
        scanDir.deleteRecursively()
    }

    private suspend fun row(id: String) = database.audioBookDao().getById(id)!!

    private fun fileOf(uri: String) = File(URI(uri).path)

    private fun picks() = pickDir.list()?.toList().orEmpty()

    private fun scanArt(id: String): String {
        scanDir.mkdirs()
        val f = File(scanDir, "$id.jpg").apply { writeBytes(byteArrayOf(7, 7)) }
        return fileUriString(f)
    }

    @Test
    fun aPickedCoverBecomesTheBooksCover() = runBlocking {
        assertTrue(kit.repository.setUserCover("A", bytes))
        val r = row("A")
        val path = r.localCoverPath!!
        assertTrue(isUserPickedCover(path, pickDir))
        assertArrayEquals(bytes, fileOf(path).readBytes())
        assertEquals(path, r.toDomain().effectiveCoverPath)
        assertNull(r.coverPath)
    }

    @Test
    fun aPickedCoverSurvivesARescan() = runBlocking {
        kit.repository.setUserCover("A", bytes)
        val pick = row("A").localCoverPath
        kit.repository.importLocalBooksCarryingMoves(LIB, listOf(a), listOf("A"), listOf("A"))
        assertEquals(pick, row("A").localCoverPath)
    }

    @Test
    fun aRescanThatFindsNewArtKeepsThePickAndRefreshesTheOriginal() = runBlocking {
        kit.repository.setUserCover("A", bytes)
        val pick = row("A").localCoverPath
        val art = scanArt("A")
        kit.repository.importLocalBooksCarryingMoves(LIB, listOf(a.copy(coverPath = art)), listOf("A"), listOf("A"))
        assertEquals(pick, row("A").localCoverPath)
        assertEquals(art, row("A").coverPath)
        assertEquals(pick, row("A").toDomain().effectiveCoverPath)
    }

    @Test
    fun aMovedBookCarriesItsPick() = runBlocking {
        kit.repository.setUserCover("A", bytes)
        val a2 = kit.scanned("A2", "Moved/Dracula", "Dracula", tracks)
        kit.repository.importLocalBooksCarryingMoves(LIB, listOf(a2), existingIds = listOf("A"), seenIds = listOf("A2"))
        kit.repository.removeMissingLocalBooks(LIB, listOf("A2"))

        val moved = row("A2").localCoverPath!!
        assertTrue(isUserPickedCover(moved, pickDir))
        assertTrue(fileOf(moved).name.startsWith("A2-"))
        assertArrayEquals(bytes, fileOf(moved).readBytes())
        val old = row("A").localCoverPath!!
        assertNotEquals(old, moved)
        assertTrue(fileOf(old).isFile)
    }

    @Test
    fun aSetAsideBookKeepsItsPickAndTheNewBookStartsWithout() = runBlocking {
        kit.repository.setUserCover("A", bytes)
        val pick = row("A").localCoverPath!!
        val archivedAt = System.currentTimeMillis() - 5 * 86_400_000L
        database.audioBookDao().archiveByIds(listOf("A"), archivedAt)
        val different = kit.scanned(
            "A", "Dracula", "New Book",
            // Different files and a different count, so it is not the same book (#47).
            listOf(Triple("new_01.mp3", 2_000L, null as String?), Triple("new_02.mp3", 3_000L, null as String?), Triple("new_03.mp3", 4_000L, null as String?)),
        )
        kit.repository.importLocalBooksCarryingMoves(LIB, listOf(different), emptyList(), listOf("A"))
        kit.repository.removeMissingLocalBooks(LIB, listOf("A"))

        val aside = row("A~$archivedAt")
        assertEquals(pick, aside.localCoverPath)
        assertTrue(fileOf(pick).isFile)
        assertNull(row("A").localCoverPath)
    }

    @Test
    fun aSecondPickReplacesTheFirstAndDeletesItsFile() = runBlocking {
        kit.repository.setUserCover("A", bytes, nowMs = 1)
        kit.repository.setUserCover("A", byteArrayOf(4, 5), nowMs = 2)
        assertEquals(listOf("A-2.jpg"), picks())
        assertEquals("A-2.jpg", fileOf(row("A").localCoverPath!!).name)
    }

    @Test
    fun useTheOriginalCoverRestoresTheScansArt() = runBlocking {
        val art = scanArt("A")
        kit.repository.importLocalBooks(LIB, listOf(a.copy(coverPath = art)))
        kit.repository.setUserCover("A", bytes)
        assertTrue(kit.repository.clearUserCover("A"))
        val r = row("A")
        assertNull(r.localCoverPath)
        assertEquals(art, r.toDomain().effectiveCoverPath)
        assertTrue(picks().isEmpty())
    }

    @Test
    fun useTheOriginalCoverWithNoScanArtShowsThePlaceholder() = runBlocking {
        kit.repository.setUserCover("A", bytes)
        assertTrue(kit.repository.clearUserCover("A"))
        assertNull(row("A").toDomain().effectiveCoverPath)
    }

    @Test
    fun useTheOriginalCoverDoesNothingWithoutAPick() = runBlocking {
        assertFalse(kit.repository.clearUserCover("A"))
    }

    @Test
    fun deleteForeverRemovesThePickFile() = runBlocking {
        kit.repository.setUserCover("A", bytes)
        kit.repository.deleteLocalBookForever("A")
        assertTrue(picks().isEmpty())
    }

    @Test
    fun aServerBookRefusesAPick() = runBlocking {
        database.audioBookDao().upsert(AudioBookEntity(id = "S", title = "Server", libraryId = "srv", isLocal = 0))
        assertFalse(kit.repository.setUserCover("S", bytes))
        assertTrue(picks().isEmpty())
    }
}
