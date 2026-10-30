package com.ninelivesaudio.app.data.local

/**
 * Every BMP character Kotlin's [Char.isWhitespace] accepts, bound as `:ws`
 * so SQL's ltrim skips exactly what the Library rail's letterLabelFor skips.
 * Built on the device, so it matches that device's own character tables.
 * No surrogate is whitespace, so nothing outside the BMP is missed.
 */
internal val AUTO_TRIM_CHARS: String = buildString {
    for (code in 0..0xFFFF) {
        val c = code.toChar()
        if (c.isWhitespace()) append(c)
    }
}

/**
 * The live books of one Android Auto branch, each with its folder keys:
 * `k1` is its Library rail letter ("#" or "A" to "Z") and `k2` the rail
 * letter of what follows ("#" or "A" to "Z"). This is SQL's copy of
 * com.ninelivesaudio.app.service.autoTitleKey, read over the folded title
 * SearchText stores first (see bookSearchText), and an instrumented parity
 * test (AutoFolderQueryInstrumentedTest) fails if the two ever drift.
 *
 * The title is cut at the first char(31) field separator before trimming.
 * char(31) counts as whitespace, so trimming first would read the author of
 * a blank title.
 *
 * Binds `:libraryId`, `:isLocal`, `:downloadedOnly` (0 or 1) and `:ws`
 * ([AUTO_TRIM_CHARS]).
 */
internal const val AUTO_KEYED_ROWS = """
    SELECT Id, Title, Author, Narrator, CoverPath, LocalCoverPath, GenresJson,
        CASE WHEN c1 BETWEEN 'a' AND 'z' THEN upper(c1) ELSE '#' END AS k1,
        CASE WHEN c2 BETWEEN 'a' AND 'z' THEN upper(c2) ELSE '#' END AS k2
    FROM (
        SELECT *, substr(t, 1, 1) AS c1, substr(ltrim(substr(t, 2), :ws), 1, 1) AS c2
        FROM (
            SELECT Id, Title, Author, Narrator, CoverPath, LocalCoverPath, GenresJson,
                ltrim(substr(SearchText, 1, instr(SearchText || char(31), char(31)) - 1), :ws) AS t
            FROM AudioBooks
            WHERE LibraryId = :libraryId AND IsLocal = :isLocal AND ArchivedAt IS NULL
                AND (:downloadedOnly = 0 OR IsDownloaded = 1)
        )
    )
"""
