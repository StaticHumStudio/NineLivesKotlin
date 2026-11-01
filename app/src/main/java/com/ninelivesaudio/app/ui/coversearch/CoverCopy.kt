package com.ninelivesaudio.app.ui.coversearch

/**
 * Every word Find a cover shows, in one place. Draft copy, pending Jeff's approval
 * (ids C1 to C14 in the 2.2.0 cover search plan, plus C15 for the audiobookcovers.com credit).
 */
object CoverCopy {
    /** C1 (book page button) and C3 (cover screen title). */
    const val FIND_A_COVER = "Find a cover"

    /** C2, the line under the book page button. */
    const val PRIVACY_LINE = "Searching sends this book's title and author to audiobookcovers.com and Open Library."

    /** C4 */
    const val FIELD_LABEL = "Title and author"

    /** C5 */
    const val SEARCH = "Search"

    /** C6 */
    const val PICK_FROM_PHOTOS = "Pick from my photos"

    /** C7 */
    const val SEARCHING = "Searching for covers..."

    /** C8 */
    const val NO_MATCHES = "No covers found. Try a shorter title."

    /** C9, when every source failed. */
    const val UNREACHABLE = "Couldn't reach the cover services. Check your connection, or pick from your photos."

    /** C10, the credit under each tile, one per source. */
    const val SOURCE_AUDIOBOOK_COVERS = "audiobookcovers.com"
    const val SOURCE_OPEN_LIBRARY = "Open Library"

    /** C15, the artist credit under an audiobookcovers.com preview. */
    const val AUDIOBOOK_COVERS_CREDIT = "Cover art by r/audiobookcovers"

    /** C11 */
    const val USE_THIS_COVER = "Use this cover"

    /** C12 */
    const val CANCEL = "Cancel"

    /** C13 */
    const val SAVE_FAILED = "That cover didn't save. Try another one."

    /** C14, on the book page. */
    const val USE_ORIGINAL = "Use the original cover"
}
