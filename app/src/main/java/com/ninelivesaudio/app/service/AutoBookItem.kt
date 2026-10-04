package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.local.searchTextLikePattern
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
    // A page bigger than the cap is read as one capped list. Capping the
    // limit but not the offset would skip the books between the cap and the
    // next page start, so later pages of an oversized size are empty instead.
    if (pageSize > AUTO_BROWSE_MAX_PAGE_SIZE) {
        if (page > 0) return emptyList()
        return load(libraryId, settings.appMode == AppMode.LOCAL, AUTO_BROWSE_MAX_PAGE_SIZE, 0)
    }
    val offset = page.toLong() * pageSize.toLong()
    if (offset > Int.MAX_VALUE) return emptyList()
    return load(libraryId, settings.appMode == AppMode.LOCAL, pageSize, offset.toInt())
}

/**
 * Most search hits Auto ever lists. A one letter query in a big library would
 * otherwise build a row and fetch a cover for thousands of books.
 */
internal const val AUTO_SEARCH_RESULT_CAP = 100

/**
 * [query] as a LIKE "contains" pattern for the folded search column, made by
 * [searchTextLikePattern], so accents and case never cause a miss and `%`,
 * `_` and `\` match themselves. Null for a blank query, which searches
 * nothing.
 */
internal fun autoSearchPattern(query: String): String? =
    query.trim().takeIf { it.isNotEmpty() }?.let(::searchTextLikePattern)

/**
 * One page of Auto search hits in the active library and source, never past
 * [AUTO_SEARCH_RESULT_CAP]. A blank query or no active library reads nothing.
 */
internal suspend fun <T> autoSearchPage(
    settings: AppSettings,
    query: String,
    page: Int,
    pageSize: Int,
    load: suspend (libraryId: String, isLocal: Boolean, pattern: String, limit: Int, offset: Int) -> List<T>,
): List<T> {
    val libraryId = settings.activeLibraryId ?: return emptyList()
    val pattern = autoSearchPattern(query) ?: return emptyList()
    if (page < 0 || pageSize <= 0) return emptyList()
    val offset = page.toLong() * pageSize.toLong()
    if (offset >= AUTO_SEARCH_RESULT_CAP) return emptyList()
    val limit = minOf(pageSize.toLong(), AUTO_SEARCH_RESULT_CAP - offset).toInt()
    return load(libraryId, settings.appMode == AppMode.LOCAL, pattern, limit, offset.toInt())
}

/** How many hits [autoSearchPage] can list for [query], at most [AUTO_SEARCH_RESULT_CAP]. */
internal suspend fun autoSearchCount(
    settings: AppSettings,
    query: String,
    count: suspend (libraryId: String, isLocal: Boolean, pattern: String, cap: Int) -> Int,
): Int {
    val libraryId = settings.activeLibraryId ?: return 0
    val pattern = autoSearchPattern(query) ?: return 0
    return count(libraryId, settings.appMode == AppMode.LOCAL, pattern, AUTO_SEARCH_RESULT_CAP)
        .coerceIn(0, AUTO_SEARCH_RESULT_CAP)
}
