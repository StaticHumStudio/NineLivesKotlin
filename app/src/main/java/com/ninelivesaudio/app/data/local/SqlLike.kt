package com.ninelivesaudio.app.data.local

import com.ninelivesaudio.app.domain.util.foldForSearch

/**
 * User text as a LIKE literal, for a LIKE that declares ESCAPE '\'. Without
 * it a search for "%" matched every book (and loaded the whole shelf) and
 * "_" matched any one character.
 */
internal fun escapeLike(text: String): String = buildString(text.length + 4) {
    for (c in text) {
        if (c == '\\' || c == '%' || c == '_') append('\\')
        append(c)
    }
}

/** A LIKE pattern that finds [text] anywhere, for a LIKE that declares ESCAPE '\'. */
internal fun containsLikePattern(text: String): String = "%${escapeLike(text)}%"

/**
 * A LIKE pattern that finds a typed [query] anywhere in AudioBooks.SearchText,
 * folded the way that column is (see [bookSearchText]), for a LIKE that
 * declares ESCAPE '\'.
 */
internal fun searchTextLikePattern(query: String): String = containsLikePattern(foldForSearch(query))
