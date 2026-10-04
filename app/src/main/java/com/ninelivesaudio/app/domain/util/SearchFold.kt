package com.ninelivesaudio.app.domain.util

import java.text.Normalizer

/**
 * [text] the way search compares it: lowercase with accents dropped, so
 * "Émile" and "emile", or "DUNE" and "dune", fold to the same thing. SQLite
 * LIKE folds case for ASCII only and never drops accents (issue #61), so the
 * stored search column and the typed query are both folded by this one
 * function. The Library's letter rail files titles with it too.
 *
 * Letters with no accent to drop (ø, ł, ß, æ, and friends) get their plain
 * spelling, so a search for "nesbo" finds "Nesbø". Everything else, digits,
 * punctuation, spaces and other alphabets, is kept as it is.
 */
fun foldForSearch(text: String): String {
    if (text.all { it in ' '..'@' || it in '['..'~' }) return text
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
    return buildString(decomposed.length) {
        for (c in decomposed) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            when (c) {
                'Ø', 'ø' -> append('o')
                'Ł', 'ł' -> append('l')
                'Đ', 'đ' -> append('d')
                'Ħ', 'ħ' -> append('h')
                'ı' -> append('i')
                'ß', 'ẞ' -> append("ss")
                'Æ', 'æ' -> append("ae")
                'Œ', 'œ' -> append("oe")
                'Þ', 'þ' -> append("th")
                'ς' -> append('σ')
                else -> append(c.lowercaseChar())
            }
        }
    }
}
