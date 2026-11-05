package com.ninelivesaudio.app.widget.binder

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.ui.library.SortMode
import com.ninelivesaudio.app.ui.library.sortBooks

/** Enough for a binder, and kind to a launcher's memory (design). */
internal const val BINDER_BOOK_CAP = 50

/** The Binder's books: the library's live books in the chosen Library sort, capped. */
internal fun binderBooks(books: List<AudioBook>, sort: SortMode): List<AudioBook> =
    sortBooks(books.filterNot { it.isArchived }, sort).take(BINDER_BOOK_CAP)

/** One pocket on a Binder page. Every filled pocket is a book. */
internal sealed interface BinderPocket {
    data class Book(val id: String, val title: String) : BinderPocket

    /** Padding for a short last page. */
    data object Empty : BinderPocket
}

private val BinderPocket.key: String
    get() = when (this) {
        BinderPocket.Empty -> "empty"
        is BinderPocket.Book -> "book:$id"
    }

/**
 * One page of the stack: both sides of a spread, left side first, row by row
 * (see [BinderLayout.slotOf]). [stableId] depends only on the pockets, so the
 * stack keeps its place across rebuilds at the same size.
 */
internal data class BinderPage(val pockets: List<BinderPocket>) {
    val stableId: Long
        get() {
            // FNV-1a 64 over every key: stable across processes and builds.
            var h = -0x340d631b7bdddcdbL
            for (c in pockets.joinToString("|") { it.key }) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
}

/**
 * The books in order, [perPage] to a page, a short last page padded with
 * [BinderPocket.Empty]. An empty shelf still gets one page of empty pockets.
 */
internal fun binderPages(books: List<AudioBook>, perPage: Int): List<BinderPage> {
    require(perPage > 0) { "perPage must be positive" }
    if (books.isEmpty()) return listOf(BinderPage(List(perPage) { BinderPocket.Empty }))
    return books.map { BinderPocket.Book(it.id, it.title) }
        .chunked(perPage)
        .map { chunk -> BinderPage(chunk + List(perPage - chunk.size) { BinderPocket.Empty }) }
}

/**
 * The page an arrow on page [position] of [count] turns to. The stack loops,
 * so next from the last page is the first, and back from the first is the last.
 */
internal fun binderTurnTarget(position: Int, count: Int, forward: Boolean): Int {
    if (count <= 0) return 0
    val step = if (forward) 1 else -1
    return ((position + step) % count + count) % count
}

/** "3 / 13": the page number under the discs, one based. */
internal fun binderPageNumber(position: Int, count: Int): String = "${position + 1} / $count"
