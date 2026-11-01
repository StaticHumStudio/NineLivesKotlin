package com.ninelivesaudio.app.widget.binder

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.ui.library.SortMode
import com.ninelivesaudio.app.ui.library.sortBooks

/** Enough for a binder, and kind to a launcher's memory (design). */
internal const val BINDER_BOOK_CAP = 50

/** The Binder's books: the library's live books in the chosen Library sort, capped. */
internal fun binderBooks(books: List<AudioBook>, sort: SortMode): List<AudioBook> =
    sortBooks(books.filterNot { it.isArchived }, sort).take(BINDER_BOOK_CAP)

/** One square sleeve in the Binder. */
internal sealed interface BinderPage {
    /** The Nine Lives icon, always the first sleeve. Opens the app. */
    data object Icon : BinderPage

    data class Book(val id: String, val title: String) : BinderPage

    /** Padding for an odd last spread. */
    data object Empty : BinderPage
}

private val BinderPage.key: String
    get() = when (this) {
        BinderPage.Icon -> "icon"
        BinderPage.Empty -> "empty"
        is BinderPage.Book -> "book:$id"
    }

/**
 * Two sleeves side by side with the hinge between, which is what a 2x1
 * widget shows at once (plan Design delta 2). [stableId] depends only on the
 * page ids, so the stack keeps its place across rebuilds.
 */
internal data class BinderSpread(val left: BinderPage, val right: BinderPage) {
    val stableId: Long
        get() {
            // FNV-1a 64 over both keys: stable across processes and builds.
            var h = -0x340d631b7bdddcdbL
            for (c in left.key + "|" + right.key) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
}

/** Icon first, then one page per book, two to a spread, an odd end padded with [BinderPage.Empty]. */
internal fun binderSpreads(books: List<AudioBook>): List<BinderSpread> {
    val pages = listOf<BinderPage>(BinderPage.Icon) + books.map { BinderPage.Book(it.id, it.title) }
    return pages.chunked(2).map { BinderSpread(it[0], it.getOrElse(1) { BinderPage.Empty }) }
}
