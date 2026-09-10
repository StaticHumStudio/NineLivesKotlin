package com.ninelivesaudio.app.ui.dossier

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.ListeningSession
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DossierBookQueryInstrumentedTest {

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
    fun selectedLibraryAndSourceQueryMatchesFormerScopeAndPreservesDossierPolicy() = runBlocking {
        val activeLive = richBook(id = "active-live", title = "A Active")
        val activeArchived = richBook(
            id = "active-archived",
            title = "B Archived",
            archivedAt = 1_725_769_200_000L,
        )
        val wrongSource = richBook(
            id = "wrong-source",
            title = "C Server",
            isLocal = 0,
        )
        val wrongLibrary = richBook(
            id = "wrong-library",
            title = "D Other Library",
            libraryId = "other-library",
        )
        database.audioBookDao().upsertAll(
            listOf(activeLive, activeArchived, wrongSource, wrongLibrary),
        )

        val settings = AppSettings(
            appMode = AppMode.LOCAL,
            selectedLocalLibraryId = ACTIVE_LIBRARY_ID,
            selectedLibraryId = "server-library",
        )
        val formerScope = dossierBooksInActiveScope(
            database.audioBookDao().getAll().map { it.toDomain() },
            settings,
        )
        val queryScope = database.audioBookDao()
            .getByLibraryAndSource(ACTIVE_LIBRARY_ID, isLocal = 1)
            .map { it.toDomain() }

        assertEquals(formerScope, queryScope)
        assertEquals(listOf("active-live", "active-archived"), queryScope.map { it.id })
        assertEquals("rich-file.mp3", queryScope.first().audioFiles.single().filename)
        assertEquals("Rich Chapter", queryScope.first().chapters.single().title)
        assertEquals(listOf("history", "science"), queryScope.first().genres)
        assertTrue(queryScope.single { it.id == "active-archived" }.isArchived)
        assertFalse(queryScope.any { it.id == "wrong-source" || it.id == "wrong-library" })

        val scopedSessions = dossierSessionsInActiveScope(
            sessions = listOf(
                sessionFor("active-live"),
                sessionFor("active-archived"),
                sessionFor("unknown-historical-book"),
            ),
            scopedBookIds = queryScope.mapTo(mutableSetOf()) { it.id },
        )

        assertEquals(
            listOf("active-live", "active-archived"),
            scopedSessions.map { it.libraryItemId },
        )
    }

    @Test
    fun selectedServerLibraryAndSourceQueryMatchesFormerScope() = runBlocking {
        val activeServer = richBook(
            id = "active-server",
            title = "A Server",
            libraryId = ACTIVE_SERVER_LIBRARY_ID,
            isLocal = 0,
        )
        val wrongSource = richBook(
            id = "wrong-local-source",
            title = "B Local",
            libraryId = ACTIVE_SERVER_LIBRARY_ID,
        )
        val wrongLibrary = richBook(
            id = "wrong-server-library",
            title = "C Other Server",
            libraryId = "other-server-library",
            isLocal = 0,
        )
        database.audioBookDao().upsertAll(listOf(activeServer, wrongSource, wrongLibrary))

        val settings = AppSettings(
            appMode = AppMode.AUDIOBOOKSHELF,
            selectedLibraryId = ACTIVE_SERVER_LIBRARY_ID,
            selectedLocalLibraryId = ACTIVE_LIBRARY_ID,
        )
        val formerScope = dossierBooksInActiveScope(
            database.audioBookDao().getAll().map { it.toDomain() },
            settings,
        )
        val queryScope = database.audioBookDao()
            .getByLibraryAndSource(ACTIVE_SERVER_LIBRARY_ID, isLocal = 0)
            .map { it.toDomain() }

        assertEquals(formerScope, queryScope)
        assertEquals(listOf("active-server"), queryScope.map { it.id })
        assertEquals("rich-file.mp3", queryScope.single().audioFiles.single().filename)
        assertEquals("Rich Chapter", queryScope.single().chapters.single().title)
    }

    private fun richBook(
        id: String,
        title: String,
        libraryId: String = ACTIVE_LIBRARY_ID,
        isLocal: Int = 1,
        archivedAt: Long? = null,
    ) = AudioBookEntity(
        id = id,
        libraryId = libraryId,
        isLocal = isLocal,
        title = title,
        author = "Author",
        narrator = "Narrator",
        description = "Description",
        coverPath = "https://example.test/cover.jpg",
        durationSeconds = 600.0,
        addedAt = "2024-01-02T03:04:05Z",
        audioFilesJson = """[{"Id":"audio-file","Ino":"inode","Index":2,"Duration":321.5,"Filename":"rich-file.mp3","LocalPath":"file:///book/rich-file.mp3","MimeType":"audio/mpeg","Size":1234}]""",
        currentTimeSeconds = 123.0,
        progress = 0.42,
        isFinished = 0,
        isDownloaded = 1,
        localPath = "file:///book",
        localCoverPath = "file:///book/cover.jpg",
        archivedAt = archivedAt,
        seriesName = "Series",
        seriesSequence = "2",
        genresJson = "[\"history\",\"science\"]",
        tagsJson = "[\"tag-one\",\"tag-two\"]",
        chaptersJson = """[{"Id":4,"Start":100.0,"End":200.0,"Title":"Rich Chapter"}]""",
    )

    private fun sessionFor(bookId: String) = ListeningSession(
        id = "session-$bookId",
        libraryItemId = bookId,
        currentTime = 42.seconds,
        timeListening = 12.seconds,
        startedAt = 1_725_769_200_000L,
        updatedAt = 1_725_769_212_000L,
        displayTitle = null,
    )

    private companion object {
        const val ACTIVE_LIBRARY_ID = "active-local-library"
        const val ACTIVE_SERVER_LIBRARY_ID = "active-server-library"
    }
}
