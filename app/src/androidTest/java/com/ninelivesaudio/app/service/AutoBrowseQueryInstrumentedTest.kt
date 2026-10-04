package com.ninelivesaudio.app.service

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.converter.toDomain
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Android Auto's SQL pages return what the old load, sort and drop/take did. */
@RunWith(AndroidJUnit4::class)
class AutoBrowseQueryInstrumentedTest {

    private lateinit var database: AppDatabase

    private val settings = AppSettings(appMode = AppMode.AUDIOBOOKSHELF, selectedLibraryId = LIBRARY)

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
    fun libraryPagesMatchTheFormerInMemoryPaging() = runBlocking {
        seed()
        val dao = database.audioBookDao()
        val former = browseBooksForAuto(dao.getAll().map { it.toDomain() }, settings)
            .sortedBy { it.title.lowercase() }

        for (page in 0..3) {
            val expected = former.drop(page * PAGE).take(PAGE).map { it.id }
            val actual = dao.getAutoBrowsePage(LIBRARY, isLocal = 0, limit = PAGE, offset = page * PAGE).map { it.id }
            assertEquals("page $page", expected, actual)
        }
    }

    @Test
    fun downloadedPagesMatchTheFormerInMemoryPaging() = runBlocking {
        seed()
        val dao = database.audioBookDao()
        val former = browseBooksForAuto(dao.getAll().map { it.toDomain() }, settings)
            .filter { it.isDownloaded }
            .sortedBy { it.title.lowercase() }

        for (page in 0..3) {
            val expected = former.drop(page * PAGE).take(PAGE).map { it.id }
            val actual = dao.getAutoDownloadedPage(LIBRARY, isLocal = 0, limit = PAGE, offset = page * PAGE).map { it.id }
            assertEquals("page $page", expected, actual)
        }
    }

    @Test
    fun equalTitlesNeverRepeatOrSkipAcrossPages() = runBlocking {
        val dao = database.audioBookDao()
        dao.upsertAll((0 until 9).map { book(id = "same-$it", title = "Same Title") })

        val paged = (0..2).flatMap { page ->
            dao.getAutoBrowsePage(LIBRARY, isLocal = 0, limit = 3, offset = page * 3).map { it.id }
        }

        assertEquals((0 until 9).map { "same-$it" }, paged)
    }

    private suspend fun seed() {
        // Mixed case and distinct titles, so NOCASE and lowercase() agree on order.
        val titles = (0 until 40).map { index ->
            val word = "Title %02d".format((index * 17) % 40)
            if (index % 2 == 0) word.uppercase() else word.lowercase()
        }
        val books = titles.mapIndexed { index, title ->
            book(id = "book-$index", title = title, isDownloaded = if (index % 3 == 0) 1 else 0)
        } + listOf(
            book(id = "archived", title = "A Archived", archivedAt = 1L, isDownloaded = 1),
            book(id = "other-library", title = "A Other", libraryId = "other", isDownloaded = 1),
            book(id = "local", title = "A Local", isLocal = 1, isDownloaded = 1),
        )
        database.audioBookDao().upsertAll(books)
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
        description = "A long description that the light rows never read.",
        isDownloaded = isDownloaded,
        archivedAt = archivedAt,
        genresJson = "[\"Mystery\"]",
    )

    private companion object {
        const val LIBRARY = "lib"
        const val PAGE = 7
    }
}
