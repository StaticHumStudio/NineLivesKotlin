package com.ninelivesaudio.app.ui.library

/** The short label the Library's sort chip shows, shared with the Binder's setup screen. */
internal fun SortMode.label(): String = when (this) {
    SortMode.RECENTLY_ADDED -> "Newest"
    SortMode.TITLE_AZ -> "Title A→Z"
    SortMode.TITLE_ZA -> "Title Z→A"
    SortMode.AUTHOR_AZ -> "Author A→Z"
    SortMode.AUTHOR_ZA -> "Author Z→A"
    SortMode.PROGRESS_HIGH -> "Progress ↑"
    SortMode.PROGRESS_LOW -> "Progress ↓"
    SortMode.DURATION_LONG -> "Longest"
    SortMode.DURATION_SHORT -> "Shortest"
    SortMode.RECENTLY_PLAYED -> "Recently played"
    SortMode.UNPLAYED_FIRST -> "Unplayed"
}
