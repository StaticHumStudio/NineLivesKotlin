package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.ui.library.SortMode
import com.ninelivesaudio.app.widget.binder.BinderConfig
import com.ninelivesaudio.app.widget.binder.binderSpreads
import com.ninelivesaudio.app.widget.binder.loadBinderModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FakeSource(
    private val folderOk: Boolean = true,
    private val books: List<AudioBook> = emptyList(),
) : WidgetBookSource {
    val calls = mutableListOf<String>()
    var lastLibraryId: String? = null

    override suspend fun folderAccessible(libraryId: String): Boolean {
        calls += "folderAccessible"; lastLibraryId = libraryId; return folderOk
    }
    override suspend fun countBooks(libraryId: String): Int {
        calls += "countBooks"; lastLibraryId = libraryId; return books.size
    }
    override suspend fun shelf(libraryId: String): List<AudioBook> {
        calls += "shelf"; lastLibraryId = libraryId; return books
    }
    override suspend fun recent(libraryId: String, limit: Int): List<AudioBook> {
        calls += "recent"; lastLibraryId = libraryId; return books.take(limit)
    }
}

class WidgetBookSourceTest {

    @Test fun `a locked binder never touches the library`() = runBlocking {
        val src = FakeSource(books = listOf(AudioBook(id = "b1")))
        val m = loadBinderModel(WidgetAccess.Locked, BinderConfig(), src)
        assertEquals(WidgetContent.LOCKED, m.content)
        assertEquals(emptyList<String>(), src.calls)
        assertTrue(m.books.isEmpty())
    }

    @Test fun `signed out and no library read nothing either`() = runBlocking {
        val src = FakeSource()
        loadBinderModel(WidgetAccess.SignedOut, BinderConfig(), src)
        loadBinderModel(WidgetAccess.NoLibrary(true), BinderConfig(), src)
        assertEquals(emptyList<String>(), src.calls)
    }

    @Test fun `a lost folder stops before the shelf is read`() = runBlocking {
        val src = FakeSource(folderOk = false, books = listOf(AudioBook(id = "b1")))
        val m = loadBinderModel(WidgetAccess.Library("lib_l", true), BinderConfig(), src)
        assertEquals(WidgetContent.FOLDER_LOST, m.content)
        assertEquals(listOf("folderAccessible"), src.calls)
    }

    @Test fun `a ready binder reads the chosen library in the chosen sort`() = runBlocking {
        val books = listOf(AudioBook(id = "b", title = "B"), AudioBook(id = "a", title = "A"))
        val src = FakeSource(books = books)
        val m = loadBinderModel(WidgetAccess.Library("lib_s", false), BinderConfig(sort = SortMode.TITLE_AZ), src)
        assertEquals(WidgetContent.READY, m.content)
        assertEquals(listOf("a", "b"), m.books.map { it.id })
        assertEquals("lib_s", src.lastLibraryId)
        assertEquals(binderSpreads(m.books), m.spreads)
    }

    @Test fun `a server binder never asks about folders`() = runBlocking {
        val src = FakeSource(folderOk = false, books = listOf(AudioBook(id = "b1")))
        val m = loadBinderModel(WidgetAccess.Library("lib_s", false), BinderConfig(), src)
        assertEquals(WidgetContent.READY, m.content)
        assertEquals(listOf("shelf"), src.calls)
    }

    @Test fun `an empty library shows its empty state`() = runBlocking {
        val m = loadBinderModel(WidgetAccess.Library("lib_s", false), BinderConfig(), FakeSource(books = emptyList()))
        assertEquals(WidgetContent.NO_BOOKS, m.content)
        assertTrue(m.spreads.isEmpty())
    }
}
