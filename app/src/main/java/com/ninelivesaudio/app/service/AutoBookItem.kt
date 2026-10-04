package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.local.entity.AutoBrowseRow
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import kotlinx.serialization.json.Json

/**
 * What an Android Auto row needs from a book: its text and where its cover
 * lives. Kept apart from [AudioBook] so a light browse row can never be
 * mistaken for a whole book and saved over one.
 */
internal data class AutoBookItem(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String?,
    val genre: String?,
    val coverPath: String?,
    val localCoverPath: String?,
) {
    val effectiveCoverPath: String? get() = localCoverPath ?: coverPath
}

internal fun AudioBook.toAutoBookItem(): AutoBookItem = AutoBookItem(
    id = id,
    title = title,
    author = author,
    narrator = narrator,
    genre = genres.firstOrNull(),
    coverPath = coverPath,
    localCoverPath = localCoverPath,
)

private val autoRowJson = Json { ignoreUnknownKeys = true }

internal fun AutoBrowseRow.toAutoBookItem(): AutoBookItem = AutoBookItem(
    id = id,
    title = title,
    author = author.orEmpty(),
    narrator = narrator,
    genre = genresJson
        ?.let { runCatching { autoRowJson.decodeFromString<List<String>>(it) }.getOrNull() }
        ?.firstOrNull(),
    coverPath = coverPath,
    localCoverPath = localCoverPath,
)

/**
 * Largest page an Auto list reads in one go. Browsers that do not page (a
 * legacy subscribe arrives as page 0 with Int.MAX_VALUE) would otherwise read
 * and build a row for every book in the library.
 */
internal const val AUTO_BROWSE_MAX_PAGE_SIZE = 500

/**
 * One page of an Auto list for the active library and source, read straight
 * from SQL with [load] as LIMIT and OFFSET. Nothing outside the page is read.
 */
internal suspend fun <T> autoBrowsePage(
    settings: AppSettings,
    page: Int,
    pageSize: Int,
    load: suspend (libraryId: String, isLocal: Boolean, limit: Int, offset: Int) -> List<T>,
): List<T> {
    val libraryId = settings.activeLibraryId ?: return emptyList()
    if (page < 0 || pageSize <= 0) return emptyList()
    val offset = page.toLong() * pageSize.toLong()
    if (offset > Int.MAX_VALUE) return emptyList()
    val limit = minOf(pageSize, AUTO_BROWSE_MAX_PAGE_SIZE)
    return load(libraryId, settings.appMode == AppMode.LOCAL, limit, offset.toInt())
}
