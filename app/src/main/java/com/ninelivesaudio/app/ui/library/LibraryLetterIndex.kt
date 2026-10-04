package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.util.foldForSearch
import kotlin.math.ceil

/** Where digits, symbols and anything outside A to Z are filed. */
internal const val NUMBER_SIGN_LABEL = "#"

/** Under this many rows the list is short enough to scroll, so no rail. */
internal const val MIN_ROWS_FOR_LETTER_INDEX = 40

/** A to Z and the number sign. */
private const val MAX_LETTER_LABELS = 27

/**
 * The letters a sorted Library list can be jumped to. [labels] are in list
 * order (reversed for a Z to A sort) and [offsets] holds, for each, the
 * position of the first row under it in the list of rows (headers and books
 * for a grouped list). Built once per list change by [flatLetterIndex] or
 * [groupedLetterIndex], never during scrolling.
 */
internal class LetterIndex(val labels: List<String>, val offsets: IntArray) {
    val isEmpty: Boolean get() = labels.isEmpty()

    /**
     * Which label covers the row at [position]: the last one whose first row
     * is at or before it. A position before the first label gets the first.
     */
    fun labelIndexAt(position: Int): Int {
        var low = 0
        var high = offsets.size - 1
        var found = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (offsets[mid] <= position) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    companion object {
        val Empty = LetterIndex(emptyList(), IntArray(0))
    }
}

/**
 * Whether the shelf is in alphabetical order under [sortMode], by title or
 * by author. Every other sort has no meaningful letters to jump to.
 */
internal fun sortsAlphabetically(sortMode: SortMode): Boolean = when (sortMode) {
    SortMode.TITLE_AZ, SortMode.TITLE_ZA, SortMode.AUTHOR_AZ, SortMode.AUTHOR_ZA -> true
    else -> false
}

private val ASCII_LABELS = Array(26) { ('A' + it).toString() }

/**
 * The letter a title or name is filed under, matching how the shelf sorts it
 * (on its first character, so "The Hobbit" is under T). Accents are dropped
 * by [foldForSearch], so "Émile" is under E and "Øystein" under O. Digits,
 * symbols, other alphabets and blanks share the number sign.
 */
internal fun letterLabelFor(text: String): String {
    var i = 0
    while (i < text.length && text[i].isWhitespace()) i++
    if (i == text.length) return NUMBER_SIGN_LABEL
    val c = text[i]
    return when {
        c in 'A'..'Z' -> ASCII_LABELS[c - 'A']
        c in 'a'..'z' -> ASCII_LABELS[c - 'a']
        c.isLetter() -> {
            // Filed the way search folds it, so the rail and search agree.
            val base = foldForSearch(c.toString()).firstOrNull()
            if (base != null && base in 'a'..'z') ASCII_LABELS[base - 'a'] else NUMBER_SIGN_LABEL
        }
        else -> NUMBER_SIGN_LABEL
    }
}

/**
 * One pass over [rowCount] rows, keeping the first row of each label that
 * [labelAt] names (null skips a row). Stops as soon as every letter has been
 * seen, and returns [LetterIndex.Empty] for a short list or one with a single
 * label, where a rail would be clutter.
 */
internal fun buildLetterIndex(rowCount: Int, labelAt: (Int) -> String?): LetterIndex {
    if (rowCount < MIN_ROWS_FOR_LETTER_INDEX) return LetterIndex.Empty
    val seen = HashSet<String>(MAX_LETTER_LABELS * 2)
    val labels = ArrayList<String>(MAX_LETTER_LABELS)
    val offsets = IntArray(MAX_LETTER_LABELS)
    for (row in 0 until rowCount) {
        val label = labelAt(row) ?: continue
        if (seen.add(label)) {
            offsets[labels.size] = row
            labels.add(label)
            if (labels.size == MAX_LETTER_LABELS) break
        }
    }
    if (labels.size < 2) return LetterIndex.Empty
    return LetterIndex(labels, offsets.copyOf(labels.size))
}

/** The rail for the flat shelf, filed by title or author to match [sortMode]. */
internal fun flatLetterIndex(books: List<AudioBook>, sortMode: SortMode): LetterIndex {
    if (!sortsAlphabetically(sortMode)) return LetterIndex.Empty
    val byAuthor = sortMode == SortMode.AUTHOR_AZ || sortMode == SortMode.AUTHOR_ZA
    return buildLetterIndex(books.size) { row ->
        val book = books[row]
        letterLabelFor(if (byAuthor) book.author else book.title)
    }
}

/**
 * The rail for a grouped shelf. Only the group headers carry letters, and the
 * offsets count every header and expanded book row before them.
 */
internal fun groupedLetterIndex(items: List<LibraryListItem>, sortMode: SortMode): LetterIndex {
    if (!sortsAlphabetically(sortMode)) return LetterIndex.Empty
    return buildLetterIndex(items.size) { row ->
        (items[row] as? LibraryListItem.GroupHeader)?.let { letterLabelFor(it.title) }
    }
}

/**
 * A remember key that is [value] by reference. The list of books is a key for
 * the index, and comparing two lists of 8,000 books by content on the main
 * thread is exactly the cost the index exists to avoid.
 */
internal class IdentityKey(private val value: Any) {
    override fun equals(other: Any?): Boolean = other is IdentityKey && other.value === value
    override fun hashCode(): Int = System.identityHashCode(value)
}

/** The letter under a finger [y] pixels down a rail [heightPx] tall. */
internal fun letterIndexAtTouch(y: Float, heightPx: Float, labelCount: Int): Int {
    if (labelCount <= 0 || heightPx <= 0f) return 0
    return ((y / heightPx) * labelCount).toInt().coerceIn(0, labelCount - 1)
}

/**
 * Show every [letterRailStride]th letter, so no two shown letters sit closer
 * than [minSlotPx] on a rail [heightPx] tall. Touch still maps to every letter.
 */
internal fun letterRailStride(labelCount: Int, heightPx: Float, minSlotPx: Float): Int {
    if (labelCount <= 0 || heightPx <= 0f) return 1
    return ceil(minSlotPx * labelCount / heightPx - 0.0001f).toInt().coerceAtLeast(1)
}

/** A rail letter as TalkBack should say it. */
internal fun letterRailSpokenLabel(label: String): String =
    if (label == NUMBER_SIGN_LABEL) "numbers and symbols" else label
