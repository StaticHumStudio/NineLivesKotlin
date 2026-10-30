package com.ninelivesaudio.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SQL behind the download rules: a delete clears only the book's local
 * copy, so a sync or a position save that landed meanwhile stays (#85).
 */
@RunWith(AndroidJUnit4::class)
class DownloadRowWritesInstrumentedTest {

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

    private val tracksJson = """[{"id":"a","ino":"a","index":0,"filename":"01.mp3"}]"""

    @Test
    fun clearingTheLocalCopyLeavesProgressAndMetadataAlone() = runBlocking {
        val dao = database.audioBookDao()
        dao.upsert(
            AudioBookEntity(
                id = "A",
                libraryId = "lib",
                title = "Server title",
                audioFilesJson = tracksJson,
                currentTimeSeconds = 1234.5,
                progress = 0.4,
                isDownloaded = 1,
                localPath = "/d/A",
                localCoverPath = "file:///d/A/cover.jpg",
            ),
        )

        dao.clearLocalCopy("A")

        val row = dao.getById("A")!!
        assertEquals(0, row.isDownloaded)
        assertNull(row.localPath)
        assertNull(row.localCoverPath)
        assertEquals("Server title", row.title)
        assertEquals(1234.5, row.currentTimeSeconds, 0.0)
        assertEquals(0.4, row.progress, 0.0)
        assertEquals(tracksJson, row.audioFilesJson)
    }

    @Test
    fun clearingTheLocalCopyOfAMissingBookWritesNothing() = runBlocking {
        val dao = database.audioBookDao()

        dao.clearLocalCopy("gone")

        assertNull(dao.getById("gone"))
    }

    private val newTracks = """[{"Id":"a","Ino":"a","Index":0,"Duration":60.0,"Filename":"01.mp3","LocalPath":"/d/A/01.mp3"}]"""
    private val newChapters = """[{"Id":0,"Start":0.0,"End":60.0,"Title":"One"}]"""

    @Test
    fun theBackfillLandsOnADownloadedRowWithAnEmptyManifest() = runBlocking {
        val dao = database.audioBookDao()
        dao.upsert(AudioBookEntity(id = "A", libraryId = "lib", title = "T", audioFilesJson = "[]", isDownloaded = 1, localPath = "/d/A"))

        val changed = dao.backfillDownloadManifest("A", "/d/A", newTracks, newChapters)

        assertEquals(1, changed)
        val row = dao.getById("A")!!.toDomain()
        assertEquals(listOf("/d/A/01.mp3"), row.audioFiles.map { it.localPath })
        assertEquals(listOf("One"), row.chapters.map { it.title })
    }

    @Test
    fun theBackfillNeverLandsOnARowThatWasDeletedMovedOrAlreadyHasAManifest() = runBlocking {
        val dao = database.audioBookDao()
        dao.upsert(AudioBookEntity(id = "deleted", libraryId = "lib", title = "T", audioFilesJson = "[]", isDownloaded = 0))
        dao.upsert(AudioBookEntity(id = "moved", libraryId = "lib", title = "T", audioFilesJson = "[]", isDownloaded = 1, localPath = "/d/other"))
        dao.upsert(AudioBookEntity(id = "has", libraryId = "lib", title = "T", audioFilesJson = tracksJson, isDownloaded = 1, localPath = "/d/has"))

        assertEquals(0, dao.backfillDownloadManifest("deleted", "/d/deleted", newTracks, newChapters))
        assertEquals(0, dao.backfillDownloadManifest("moved", "/d/moved", newTracks, newChapters))
        assertEquals(0, dao.backfillDownloadManifest("has", "/d/has", newTracks, newChapters))
        assertEquals(0, dao.backfillDownloadManifest("missing", "/d/x", newTracks, newChapters))

        assertEquals("[]", dao.getById("deleted")!!.audioFilesJson)
        assertEquals("[]", dao.getById("moved")!!.audioFilesJson)
        assertEquals(tracksJson, dao.getById("has")!!.audioFilesJson)
    }

    @Test
    fun theDownloadedFlagFlowFollowsADelete() = runBlocking {
        val dao = database.audioBookDao()
        dao.upsert(AudioBookEntity(id = "A", libraryId = "lib", title = "T", isDownloaded = 1, localPath = "/d/A"))

        assertEquals(1, dao.observeIsDownloaded("A").first())

        dao.clearLocalCopy("A")

        assertEquals(0, dao.observeIsDownloaded("A").first())
        assertNull(dao.observeIsDownloaded("missing").first())
    }
}
