package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.domain.model.Bookmark
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for bookmark operations.
 * Bookmarks are stored server-side only (no local caching).
 */
@Singleton
class BookmarkRepository @Inject constructor(
    private val apiService: ApiService,
) {
    suspend fun getBookmarks(itemId: String): List<Bookmark> =
        apiService.getBookmarks(itemId)

    suspend fun createBookmark(itemId: String, title: String, time: Double): Boolean =
        apiService.createBookmark(itemId, title, time)

    suspend fun deleteBookmark(itemId: String, time: Double): Boolean =
        apiService.deleteBookmark(itemId, time)
}
