package com.ninelivesaudio.app.data.local

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
