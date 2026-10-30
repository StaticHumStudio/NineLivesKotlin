package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.util.foldForSearch
import com.ninelivesaudio.app.ui.library.NUMBER_SIGN_LABEL
import com.ninelivesaudio.app.ui.library.letterLabelFor

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
