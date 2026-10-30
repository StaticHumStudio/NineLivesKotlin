package com.ninelivesaudio.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
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
}
