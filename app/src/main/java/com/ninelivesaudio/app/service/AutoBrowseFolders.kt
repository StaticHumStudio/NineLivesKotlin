package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.local.entity.AutoBrowseRow
import com.ninelivesaudio.app.domain.util.foldForSearch
import com.ninelivesaudio.app.ui.library.NUMBER_SIGN_LABEL
import com.ninelivesaudio.app.ui.library.letterLabelFor
import java.text.NumberFormat
import java.util.Locale

// ─── Android Auto browse folders ────────────────────────────────────────
//
// Android Auto shows only the first 500 rows of a list, so a Library or
// Downloaded shelf bigger than that splits into folders: one per letter of
// the Library rail, then two letter folders inside a letter that is still
// too big, then numbered parts where even two letters are not enough (a
// library heavy with "The ..." titles, or one mostly in another alphabet,
// which all files under "#"). A folder id is a plain string the car hands
// back later, maybe after a sync changed the library, so every id is
// resolved against the data at call time and a stale one never errors.

/** Most rows one Auto list ever shows. Above it a branch splits into folders. */
internal const val AUTO_FOLDER_CAP = AUTO_BROWSE_MAX_PAGE_SIZE

/** Highest part number an id may carry. Far past any real library. */
private const val AUTO_FOLDER_MAX_PART = 100_000

/** The two shelves that browse as a tree. */
internal enum class AutoBranch(val mediaId: String) {
    LIBRARY(MediaBrowseTree.LIBRARY_ID),
    DOWNLOADED(MediaBrowseTree.DOWNLOADED_ID),
}

/**
 * One node of the Library or Downloaded tree. [key] null is the branch
 * itself. Otherwise it is a rail letter ("T" or "#") or a letter and the
 * next letter ("TH", "T#"). [part] is 1-based and only ever sits under a
 * two letter key or "#".
 */
internal data class AutoFolderId(val branch: AutoBranch, val key: String?, val part: Int? = null) {
    val mediaId: String
        get() = buildString {
            append(branch.mediaId)
            if (key != null) append('/').append(key)
            if (part != null) append('/').append(part)
        }

    companion object {
        private val SINGLE_KEY = Regex("[A-Z#]")
        private val PAIR_KEY = Regex("[A-Z][A-Z#]")
        private val PART = Regex("[1-9][0-9]{0,5}")

        /** The folder [mediaId] names, or null for anything that is not a folder id. */
        fun parse(mediaId: String): AutoFolderId? {
            val pieces = mediaId.split('/')
            val branch = AutoBranch.values().firstOrNull { it.mediaId == pieces[0] } ?: return null
            if (pieces.size == 1) return AutoFolderId(branch, null)
            if (pieces.size > 3) return null
            val key = pieces[1]
            val isPair = PAIR_KEY.matches(key)
            if (!isPair && !SINGLE_KEY.matches(key)) return null
            if (pieces.size == 2) return AutoFolderId(branch, key)
            // Parts split only a two letter key or "#". A plain letter that
            // is too big splits into two letter folders instead.
            if (!isPair && key != NUMBER_SIGN_LABEL) return null
            val partText = pieces[2]
            if (!PART.matches(partText)) return null
            val part = partText.toInt()
            if (part > AUTO_FOLDER_MAX_PART) return null
            return AutoFolderId(branch, key, part)
        }
    }
}

/**
 * The folder key [title] files under: at [length] 1 its Library rail letter
 * ([letterLabelFor], so "The Hobbit" is under T and digits, symbols and
 * other alphabets under "#"), at length 2 that letter and the rail letter
 * of what follows it ("TH"). "#" never splits further.
 *
 * Worked out on the title folded by [foldForSearch], the same text the
 * AudioBooks.SearchText column stores first, so SQL can file a row the same
 * way without calling Kotlin (see AutoBucketSql).
 */
internal fun autoTitleKey(title: String, length: Int): String {
    val folded = foldForSearch(title)
    val first = letterLabelFor(folded)
    if (length <= 1 || first == NUMBER_SIGN_LABEL) return first
    var i = 0
    while (i < folded.length && folded[i].isWhitespace()) i++
    return first + letterLabelFor(folded.substring(i + 1))
}

// ─── The tree planner ───────────────────────────────────────────────────

/**
 * Where the tree reads its books: one branch (Library or Downloaded) of the
 * active library and source. Counts and pages come straight from SQL in the
 * app, so nothing outside one page is ever loaded.
 */
internal interface AutoFolderSource {
    /** Every book in the branch. */
    suspend fun countAll(): Int

    /** Books per rail letter, "#" and "A" to "Z". Empty letters are absent. */
    suspend fun letterCounts(): Map<String, Int>

    /** Books per two letter key under [letter], "T#" and "TA" to "TZ" for "T". Empty keys are absent. */
    suspend fun pairCounts(letter: String): Map<String, Int>

    /** Books under [key] (null is the whole branch), Title NOCASE then Id, as LIMIT and OFFSET. */
    suspend fun page(key: String?, limit: Int, offset: Int): List<AutoBrowseRow>
}

/** One row of an Auto list: a folder to open or a book to play. */
internal sealed interface AutoBrowseChild {
    data class Folder(val id: AutoFolderId, val title: String, val subtitle: String?) : AutoBrowseChild
    data class Book(val row: AutoBrowseRow) : AutoBrowseChild
}

/** A LIMIT and OFFSET into a key's books. */
internal data class AutoWindow(val offset: Int, val limit: Int)

/**
 * Rows [start, end) of a key's books for one requested page, or null when
 * the page reads nothing. A page never reaches past [end], so paging inside
 * a part never crosses into the next part, and never reads more than
 * [AUTO_FOLDER_CAP]. A page size above the cap (a legacy subscribe arrives
 * as page 0 with Int.MAX_VALUE) is read as one capped list, and its later
 * pages are empty: capping the limit but not the offset would skip the rows
 * between the cap and the next page start.
 */
internal fun autoLeafWindow(start: Int, end: Int, page: Int, pageSize: Int): AutoWindow? {
    if (page < 0 || pageSize <= 0 || end <= start) return null
    if (pageSize > AUTO_FOLDER_CAP) {
        if (page > 0) return null
        return AutoWindow(start, minOf(AUTO_FOLDER_CAP, end - start))
    }
    val offset = start.toLong() + page.toLong() * pageSize.toLong()
    if (offset >= end) return null
    val limit = minOf(pageSize.toLong(), end - offset, AUTO_FOLDER_CAP.toLong()).toInt()
    return AutoWindow(offset.toInt(), limit)
}

private val LETTER_ORDER: List<String> = listOf(NUMBER_SIGN_LABEL) + ('A'..'Z').map { it.toString() }

private fun pairOrder(letter: String): List<String> = LETTER_ORDER.map { letter + it }

/** How many books are under [key] right now, read the way the tree files them. */
private suspend fun autoKeyCount(key: String, source: AutoFolderSource): Int =
    if (key.length == 1) source.letterCounts()[key] ?: 0
    else source.pairCounts(key.substring(0, 1))[key] ?: 0

private fun partCount(n: Int): Int = ((n.toLong() + AUTO_FOLDER_CAP - 1) / AUTO_FOLDER_CAP).toInt()

/** Items [page] of a folder list of [size], as a range, or null for none. */
private fun folderSlice(size: Int, page: Int, pageSize: Int): IntRange? {
    if (page < 0 || pageSize <= 0) return null
    val from = page.toLong() * pageSize.toLong()
    if (from >= size) return null
    val to = minOf(size.toLong(), from + pageSize.toLong())
    return from.toInt() until to.toInt()
}

/**
 * What [id] shows right now, one page of it. A branch or folder at or under
 * [AUTO_FOLDER_CAP] books lists its books, so a small library stays one flat
 * list. Above the cap a branch lists its letters, a letter its two letter
 * keys, and "#" or a two letter key numbered parts of at most the cap. Every
 * id is resolved against [source] at call time: a folder from a bigger
 * library lists the books it has now, and a part past the end lists nothing.
 */
internal suspend fun autoChildren(
    id: AutoFolderId,
    source: AutoFolderSource,
    page: Int,
    pageSize: Int,
    locale: Locale = Locale.getDefault(),
): List<AutoBrowseChild> {
    if (page < 0 || pageSize <= 0) return emptyList()
    val key = id.key
    val part = id.part
    if (key == null) {
        val n = source.countAll()
        if (n <= AUTO_FOLDER_CAP) return leaf(null, 0, n, source, page, pageSize)
        val counts = source.letterCounts()
        val letters = LETTER_ORDER.filter { (counts[it] ?: 0) > 0 }
        val slice = folderSlice(letters.size, page, pageSize) ?: return emptyList()
        return slice.map { i ->
            val letter = letters[i]
            AutoBrowseChild.Folder(AutoFolderId(id.branch, letter), letter, autoFolderSubtitle(letter, counts.getValue(letter), locale))
        }
    }
    if (part != null) {
        val n = autoKeyCount(key, source)
        val start = (part - 1).toLong() * AUTO_FOLDER_CAP
        if (start >= n) return emptyList()
        val end = minOf(part.toLong() * AUTO_FOLDER_CAP, n.toLong()).toInt()
        return leaf(key, start.toInt(), end, source, page, pageSize)
    }
    val n = autoKeyCount(key, source)
    if (n <= 0) return emptyList()
    if (n <= AUTO_FOLDER_CAP) return leaf(key, 0, n, source, page, pageSize)
    if (key.length == 1 && key != NUMBER_SIGN_LABEL) {
        val counts = source.pairCounts(key)
        val pairs = pairOrder(key).filter { (counts[it] ?: 0) > 0 }
        val slice = folderSlice(pairs.size, page, pageSize) ?: return emptyList()
        return slice.map { i ->
            val pair = pairs[i]
            val pairId = AutoFolderId(id.branch, pair)
            AutoBrowseChild.Folder(pairId, autoFolderTitle(pairId), autoFolderSubtitle(pair, counts.getValue(pair), locale))
        }
    }
    val slice = folderSlice(partCount(n), page, pageSize) ?: return emptyList()
    return slice.map { i ->
        val partId = AutoFolderId(id.branch, key, i + 1)
        val firstTitle = source.page(key, 1, i * AUTO_FOLDER_CAP).firstOrNull()?.title
        AutoBrowseChild.Folder(partId, autoFolderTitle(partId), firstTitle?.let { "From $it" })
    }
}

private suspend fun leaf(
    key: String?,
    start: Int,
    end: Int,
    source: AutoFolderSource,
    page: Int,
    pageSize: Int,
): List<AutoBrowseChild> {
    val window = autoLeafWindow(start, end, page, pageSize) ?: return emptyList()
    return source.page(key, window.limit, window.offset).map { AutoBrowseChild.Book(it) }
}

/** How many rows [autoChildren] lists for [id] with no paging, counted without building any. */
internal suspend fun autoChildCount(id: AutoFolderId, source: AutoFolderSource): Int {
    val key = id.key
    val part = id.part
    if (key == null) {
        val n = source.countAll()
        if (n <= AUTO_FOLDER_CAP) return n
        return source.letterCounts().count { it.key in LETTER_ORDER && it.value > 0 }
    }
    val n = autoKeyCount(key, source)
    if (part != null) {
        val start = (part - 1).toLong() * AUTO_FOLDER_CAP
        if (start >= n) return 0
        return minOf(AUTO_FOLDER_CAP.toLong(), n - start).toInt()
    }
    if (n <= 0) return 0
    if (n <= AUTO_FOLDER_CAP) return n
    if (key.length == 1 && key != NUMBER_SIGN_LABEL) {
        val pairs = pairOrder(key)
        return source.pairCounts(key).count { it.key in pairs && it.value > 0 }
    }
    return partCount(n)
}

// Draft copy, pending Jeff's copy sitting: folder titles and subtitles.

/** The title Auto shows for [id]: "T", "#", "Th", "T#", "Th, part 2". */
internal fun autoFolderTitle(id: AutoFolderId): String {
    val key = id.key ?: return when (id.branch) {
        AutoBranch.LIBRARY -> "Library"
        AutoBranch.DOWNLOADED -> "Downloaded"
    }
    val name = if (key.length == 2) key.substring(0, 1) + key.substring(1).lowercase(Locale.ROOT) else key
    return if (id.part != null) "$name, part ${id.part}" else name
}

private fun autoFolderSubtitle(key: String, count: Int, locale: Locale): String {
    val number = NumberFormat.getIntegerInstance(locale).format(count.toLong())
    val books = if (count == 1) "$number book" else "$number books"
    return if (key == NUMBER_SIGN_LABEL) "Numbers and symbols, $books" else books
}
