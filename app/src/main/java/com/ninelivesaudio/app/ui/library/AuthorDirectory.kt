package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.LibraryAuthor

/** One Author view group: [key] tells groups apart, [title] is the header. */
data class AuthorGroup(val key: String, val title: String)

/**
 * A server library's Audiobookshelf authors, for Author view (issue #87).
 *
 * The library list sends a book's authors as one line joined with ", ", so
 * "Smith, John" (one person written last name first) and "Smith" plus "John"
 * (two people) look the same. This matches the line against the library's
 * author names instead, left to right, trying the longest run of pieces
 * first, so a known "Smith, John" is one group even when "Smith" and "John"
 * are also authors. A matched author groups by its id under the server's
 * spelling. Pieces no known name covers fall back to the old comma split
 * ([splitServerAuthorNames]), so a book is never left out of the view
 * because the list is empty, stale, or missing a name.
 */
class AuthorDirectory(authors: List<LibraryAuthor>) {

    // Each name's spelling with runs of spaces made one, to its author. The
    // first author with a spelling keeps it, so two authors sharing a name
    // are one group, as they would be by name alone.
    private val index: Map<String, LibraryAuthor> = buildMap {
        for (author in authors) {
            val spelling = collapseSpaces(author.name)
            if (spelling.isNotEmpty() && spelling !in this) put(spelling, author)
        }
    }

    // The most ", " pieces any known name spans, so the search never tries a
    // longer run than a name could match.
    private val maxPieces: Int = index.keys.maxOfOrNull { it.split(", ").size }?.coerceAtMost(MAX_PIECES_PER_NAME) ?: 0

    /** The groups [authorLine] is listed under, in line order, each once. */
    fun groupsFor(authorLine: String): List<AuthorGroup> {
        if (index.isEmpty()) return splitServerAuthorNames(authorLine).map { AuthorGroup(it, it) }
        val pieces = authorLine.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<AuthorGroup>(pieces.size)
        val unmatched = ArrayList<String>()
        fun flushUnmatched() {
            if (unmatched.isEmpty()) return
            splitServerAuthorNames(unmatched.joinToString(", ")).mapTo(out) { AuthorGroup(it, it) }
            unmatched.clear()
        }
        var previousMatched = false
        var start = 0
        while (start < pieces.size) {
            var end = minOf(pieces.size, start + maxPieces)
            var hit: LibraryAuthor? = null
            while (end > start) {
                hit = index[collapseSpaces(pieces.subList(start, end).joinToString(", "))]
                if (hit != null) break
                end--
            }
            if (hit == null) {
                if (previousMatched && isNameSuffix(pieces[start])) {
                    // "Vonnegut, Jr." after a matched "Kurt Vonnegut": the suffix
                    // belongs to that author, it is not a second person.
                    previousMatched = false
                } else {
                    unmatched += pieces[start]
                    previousMatched = false
                }
                start++
            } else {
                previousMatched = true
                flushUnmatched()
                out += AuthorGroup(AUTHOR_ID_KEY_PREFIX + hit.id, hit.name)
                start = end
            }
        }
        flushUnmatched()
        return out.distinctBy { it.key }
    }

    companion object {
        /** No author list: every line splits on its commas, as before #87. */
        val EMPTY = AuthorDirectory(emptyList())
    }
}

/** Group keys for a matched author start with this, so they never read as a name. */
internal const val AUTHOR_ID_KEY_PREFIX = "author-id:"

/**
 * The most ", " pieces one author name is matched across. Real names have
 * one or two commas ("King, Martin Luther, Jr."), this only bounds the work
 * per book when a library holds a junk name full of commas.
 */
private const val MAX_PIECES_PER_NAME = 8

/**
 * The Author view groups for [book]. No author is [UNKNOWN_AUTHOR_GROUP]. A
 * local book's author comes from its tags or folder, where "Tolkien, J.R.R."
 * is one person written last name first, so it stays whole and never meets
 * the server's list. A server book is matched against [authors].
 */
internal fun authorGroups(book: AudioBook, authors: AuthorDirectory): List<AuthorGroup> {
    val author = book.author.trim()
    if (author.isEmpty()) return listOf(AuthorGroup(UNKNOWN_AUTHOR_GROUP, UNKNOWN_AUTHOR_GROUP))
    if (book.isLocal) return listOf(AuthorGroup(author, author))
    return authors.groupsFor(author).ifEmpty { listOf(AuthorGroup(UNKNOWN_AUTHOR_GROUP, UNKNOWN_AUTHOR_GROUP)) }
}

/** [name] trimmed, with each run of spaces inside it made one space. */
internal fun collapseSpaces(name: String): String {
    val trimmed = name.trim()
    return buildString(trimmed.length) {
        var lastWasSpace = false
        for (c in trimmed) {
            val isSpace = c.isWhitespace()
            if (!isSpace) append(c) else if (!lastWasSpace) append(' ')
            lastWasSpace = isSpace
        }
    }
}
