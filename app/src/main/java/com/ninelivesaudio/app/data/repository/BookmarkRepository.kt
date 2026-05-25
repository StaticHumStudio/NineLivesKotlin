package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.local.dao.LocalBookmarkDao
import com.ninelivesaudio.app.data.local.entity.LocalBookmarkEntity
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.Bookmark
import com.ninelivesaudio.app.service.SettingsManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of bookmarks for the player.
 *
 * In LOCAL mode, reads from the LocalBookmarks Room table populated by the
 * player when the user marks a moment in a scanned-local file.
 *
 * In AUDIOBOOKSHELF mode, delegates to ApiService which round-trips
 * /api/me/item/{itemId}/bookmark.
 *
 * The repository owns the dispatch so callers (PlayerViewModel etc.) don't
 * need to know which backend is live — they just call get/create/delete with
 * the audioBookId they already have.
 */
@Singleton
class BookmarkRepository @Inject constructor(
    private val apiService: ApiService,
    private val bookmarkDao: LocalBookmarkDao,
    private val settingsManager: SettingsManager,
) {
    suspend fun getBookmarks(itemId: String): List<Bookmark> {
        return if (isLocal()) {
            bookmarkDao.getByAudioBookId(itemId).map { it.toDomain() }
        } else {
            apiService.getBookmarks(itemId)
        }
    }

    suspend fun createBookmark(itemId: String, title: String, time: Double): Boolean {
        return if (isLocal()) {
            val now = System.currentTimeMillis()
            // REPLACE on AudioBookId+Time keeps the dedupe semantics the
            // server enforces (one bookmark per second).
            bookmarkDao.insert(
                LocalBookmarkEntity(
                    audioBookId = itemId,
                    title = title,
                    time = time,
                    createdAt = now,
                )
            )
            true
        } else {
            apiService.createBookmark(itemId, title, time)
        }
    }

    suspend fun deleteBookmark(itemId: String, time: Double): Boolean {
        return if (isLocal()) {
            bookmarkDao.deleteByBookAndTime(itemId, time) > 0
        } else {
            apiService.deleteBookmark(itemId, time)
        }
    }

    private fun isLocal(): Boolean =
        settingsManager.currentSettings.appMode == AppMode.LOCAL

    private fun LocalBookmarkEntity.toDomain(): Bookmark = Bookmark(
        id = "local-$id",
        libraryItemId = audioBookId,
        title = title,
        time = time,
        createdAt = createdAt,
    )
}
