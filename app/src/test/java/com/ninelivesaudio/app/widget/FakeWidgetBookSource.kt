package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.domain.model.AudioBook

/** A [WidgetBookSource] that records which reads ran, shared by the widget tests. */
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
