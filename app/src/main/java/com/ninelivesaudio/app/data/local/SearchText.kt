package com.ninelivesaudio.app.data.local

import com.ninelivesaudio.app.domain.util.foldForSearch

/**
 * Keeps the searched fields apart in the search column. A query cannot type
 * it, so "dune frank" never matches across a title and an author.
 */
private const val SEARCH_FIELD_SEPARATOR = '\u001F'

/**
 * The AudioBooks.SearchText value for a book: title, author, series and
 * narrator, each folded by [foldForSearch]. The Library search and Android
 * Auto search match a folded query against this one column, so accents and
 * case never cause a miss (issue #61). Filled on every write by
 * [com.ninelivesaudio.app.data.local.entity.AudioBookEntity], and for rows
 * that predate it by the 9 to 10 migration.
 */
internal fun bookSearchText(title: String?, author: String?, seriesName: String?, narrator: String?): String =
    buildString {
        append(foldForSearch(title.orEmpty()))
        append(SEARCH_FIELD_SEPARATOR)
        append(foldForSearch(author.orEmpty()))
        append(SEARCH_FIELD_SEPARATOR)
        append(foldForSearch(seriesName.orEmpty()))
        append(SEARCH_FIELD_SEPARATOR)
        append(foldForSearch(narrator.orEmpty()))
    }
