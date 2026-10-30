package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.local.AUTO_TRIM_CHARS
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.entity.AutoBrowseRow

/**
 * The Android Auto tree's books for one [branch] of one library and source,
 * counted and paged in SQL. The whole branch reads through the same queries
 * the flat list always used, and a folder key through the keyed queries
 * (see com.ninelivesaudio.app.data.local.AUTO_KEYED_ROWS).
 */
internal class RoomAutoFolderSource(
    private val dao: AudioBookDao,
    private val libraryId: String,
    isLocal: Boolean,
    branch: AutoBranch,
) : AutoFolderSource {
    private val isLocal = if (isLocal) 1 else 0
    private val downloadedOnly = branch == AutoBranch.DOWNLOADED
    private val downloadedFlag = if (downloadedOnly) 1 else 0

    override suspend fun countAll(): Int =
        if (downloadedOnly) dao.countDownloadedByLibrary(libraryId, isLocal)
        else dao.countByLibraryAndSource(libraryId, isLocal)

    override suspend fun letterCounts(): Map<String, Int> =
        dao.countAutoLetters(libraryId, isLocal, downloadedFlag, AUTO_TRIM_CHARS)
            .associate { it.bucket to it.count }

    override suspend fun pairCounts(letter: String): Map<String, Int> =
        dao.countAutoPairs(libraryId, isLocal, downloadedFlag, AUTO_TRIM_CHARS, letter)
            .associate { it.bucket to it.count }

    override suspend fun page(key: String?, limit: Int, offset: Int): List<AutoBrowseRow> = when {
        key == null && downloadedOnly -> dao.getAutoDownloadedPage(libraryId, isLocal, limit, offset)
        key == null -> dao.getAutoBrowsePage(libraryId, isLocal, limit, offset)
        else -> dao.getAutoBucketPage(
            libraryId = libraryId,
            isLocal = isLocal,
            downloadedOnly = downloadedFlag,
            ws = AUTO_TRIM_CHARS,
            first = key.substring(0, 1),
            second = key.getOrNull(1)?.toString(),
            limit = limit,
            offset = offset,
        )
    }
}
