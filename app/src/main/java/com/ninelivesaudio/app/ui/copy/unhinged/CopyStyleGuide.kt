package com.ninelivesaudio.app.ui.copy.unhinged

/**
 * Copy Style Guide — Dual-Layer Labels by Screen
 *
 * This file contains the canonical copy for all screens in Normal, Ritual, and Unhinged modes.
 * Each entry has a literal primary label and optional flavor subtitles for Ritual/Unhinged.
 *
 * **Ground Rules**:
 * 1. Primary labels are always literal - user must understand at a glance
 * 2. Flavor text is always secondary - smaller, lower contrast, beneath literal label
 * 3. Screen readers announce literal labels only
 * 4. Destructive actions are always plain - no flavor, no ambiguity, ever
 * 5. Confirmations stay literal - optional flavor may appear below, but action text is crystal clear
 */

object CopyStyleGuide {

    // ═══════════════════════════════════════════════════════════════
    //  Home / Dashboard
    // ═══════════════════════════════════════════════════════════════

    object Home {
        const val CONTINUE_LISTENING = "Continue Listening"
        const val CONTINUE_LISTENING_RITUAL = "Resume the thread."
        const val CONTINUE_LISTENING_UNHINGED = "It was waiting for you."

        const val RECENTLY_ADDED = "Recently Added"
        const val RECENTLY_ADDED_RITUAL = "New arrivals."
        const val RECENTLY_ADDED_UNHINGED = "Something followed you home."

        const val HOME_NAV = "Home"
        const val HOME_NAV_RITUAL = "The foyer."
        const val HOME_NAV_UNHINGED = "You are here. The floor agrees."
    }

    // ═══════════════════════════════════════════════════════════════
    //  Library
    // ═══════════════════════════════════════════════════════════════

    object Library {
        const val LIBRARY_NAV = "Library"
        const val LIBRARY_NAV_RITUAL = "The collection."
        const val LIBRARY_NAV_UNHINGED = "The shelves remember what you don't. And they take notes."

        const val ALL_BOOKS = "All Books"
        const val ALL_BOOKS_RITUAL = "The full archive."
        const val ALL_BOOKS_UNHINGED = "Every spine accounted for. The ones that moved got counted twice."

        const val AUTHORS = "Authors"
        const val AUTHORS_RITUAL = "Sorted by voice."
        const val AUTHORS_UNHINGED = "The ones who wrote it down before the ink started whispering back."

        const val SERIES = "Series"
        const val SERIES_RITUAL = "Grouped tellings."
        const val SERIES_UNHINGED = "Some doors open in order."

        const val GENRES = "Genres"
        const val GENRES_RITUAL = "Sorted by nature."
        const val GENRES_UNHINGED = "Categories are a comfort. Until they start sorting you."

        const val SORT_BY = "Sort By"
        const val SORT_BY_RITUAL = "Arrange the shelves."
        const val SORT_BY_UNHINGED = "Impose your will on the archive."

        const val FILTER = "Filter"
        const val FILTER_RITUAL = "Narrow the view."
        const val FILTER_UNHINGED = "Hide what you're not ready for."
    }

    // ═══════════════════════════════════════════════════════════════
    //  Now Playing / Player
    // ═══════════════════════════════════════════════════════════════

    object Player {
        const val NOW_PLAYING = "Now Playing"
        const val NOW_PLAYING_RITUAL = "The current telling."
        const val NOW_PLAYING_UNHINGED = "This is where you left off. The book remembers too."

        const val PLAY = "Play"
        const val PLAY_RITUAL = "Begin."
        const val PLAY_UNHINGED = "Let it speak."

        const val PAUSE = "Pause"
        const val PAUSE_RITUAL = "Hold."
        const val PAUSE_UNHINGED = "It will wait. It always waits."

        // Note: Skip Forward and Skip Back get NO flavor text
        // Transport controls should stay clean for rapid interaction
        const val SKIP_FORWARD = "Skip Forward"
        const val SKIP_BACK = "Skip Back"

        const val SPEED = "Speed"
        const val SPEED_RITUAL = "Pace of telling."
        const val SPEED_UNHINGED = "Faster won't help you understand."

        const val SLEEP_TIMER = "Sleep Timer"
        const val SLEEP_TIMER_RITUAL = "Set the hour of quiet."
        const val SLEEP_TIMER_UNHINGED = "Tell it when to stop watching."

        const val CHAPTERS = "Chapters"
        const val CHAPTERS_RITUAL = "The structure beneath."
        const val CHAPTERS_UNHINGED = "The bones of the thing."

        const val BOOKMARK = "Bookmark"
        const val BOOKMARK_RITUAL = "Mark this place."
        const val BOOKMARK_UNHINGED = "Leave a thread so you can find your way back."
    }

    // ═══════════════════════════════════════════════════════════════
    //  Downloads
    // ═══════════════════════════════════════════════════════════════

    object Downloads {
        const val DOWNLOADS_NAV = "Downloads"
        const val DOWNLOADS_NAV_RITUAL = "Stored locally."
        const val DOWNLOADS_NAV_UNHINGED = "Artifacts you've pulled from the vault."

        const val DOWNLOAD_BOOK = "Download"
        const val DOWNLOAD_BOOK_RITUAL = "Retrieve."
        const val DOWNLOAD_BOOK_UNHINGED = "Pull it through."

        const val DOWNLOADED = "Downloaded"
        const val DOWNLOADED_RITUAL = "Stored."
        const val DOWNLOADED_UNHINGED = "It's yours now. Locally, at least."

        const val DOWNLOADING = "Downloading..."
        const val DOWNLOADING_RITUAL = "Retrieving..."
        const val DOWNLOADING_UNHINGED = "Passing through the threshold..."

        const val DOWNLOAD_FAILED = "Download Failed"
        const val DOWNLOAD_FAILED_RITUAL = "Retrieval failed."
        const val DOWNLOAD_FAILED_UNHINGED = "It slipped. Try again."

        // Destructive action - NO FLAVOR
        const val REMOVE_DOWNLOAD = "Remove Download"
    }

    // ═══════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════

    object Search {
        const val SEARCH = "Search"
        const val SEARCH_RITUAL = "Find."
        const val SEARCH_UNHINGED = "Name what you seek. Watch what answers."

        const val NO_RESULTS = "No results found"
        const val NO_RESULTS_RITUAL = "Nothing surfaced."
        const val NO_RESULTS_UNHINGED = "The archive looked. It found nothing. That doesn't mean nothing's there."

        const val SEARCH_HINT_NORMAL = "Search books..."
        const val SEARCH_HINT_RITUAL = "Search the archive..."
        const val SEARCH_HINT_UNHINGED = "Type slowly. The shelves are listening through the keys."
    }

    // ═══════════════════════════════════════════════════════════════
    //  Bookmarks
    // ═══════════════════════════════════════════════════════════════

    object Bookmarks {
        const val BOOKMARKS = "Bookmarks"
        const val BOOKMARKS_RITUAL = "Marked places."
        const val BOOKMARKS_UNHINGED = "Threads left in the dark."

        const val ADD_BOOKMARK = "Add Bookmark"
        const val ADD_BOOKMARK_RITUAL = "Mark this moment."
        const val ADD_BOOKMARK_UNHINGED = "You'll want to find this again. Trust us."

        // Destructive action - NO FLAVOR
        const val DELETE_BOOKMARK = "Delete Bookmark"

        const val NO_BOOKMARKS = "No bookmarks yet"
        const val NO_BOOKMARKS_RITUAL = "Nothing marked."
        const val NO_BOOKMARKS_UNHINGED = "You haven't left any threads. The pages turn unmarked."
    }

    // ═══════════════════════════════════════════════════════════════
    //  Settings
    // ═══════════════════════════════════════════════════════════════

    object Settings {
        const val SETTINGS_NAV = "Settings"
        const val SETTINGS_NAV_RITUAL = "Configuration."
        const val SETTINGS_NAV_UNHINGED = "The inner workings."

        const val APPEARANCE = "Appearance"
        const val APPEARANCE_RITUAL = "Visual mode."
        const val APPEARANCE_UNHINGED = "What it looks like on the surface."

        const val THEME = "Theme"
        const val THEME_RITUAL = "Select a theme."
        const val THEME_UNHINGED = "Choose what the walls look like."

        const val PLAYBACK = "Playback"
        const val PLAYBACK_RITUAL = "Audio settings."
        const val PLAYBACK_UNHINGED = "How it speaks to you."

        const val STORAGE = "Storage"
        const val STORAGE_RITUAL = "File management."
        const val STORAGE_UNHINGED = "What the vault holds."

        const val ABOUT = "About"
        const val ABOUT_RITUAL = "App information."
        const val ABOUT_UNHINGED = "What we're willing to tell you."

        const val COPY_MODE_NORMAL = "Normal"
        const val COPY_MODE_RITUAL = "Ritual"
        const val COPY_MODE_RITUAL_DESC = "Measured strangeness."
        const val COPY_MODE_UNHINGED = "Unhinged"
        const val COPY_MODE_UNHINGED_DESC = "The archive speaks freely."

        const val ANOMALIES_TOGGLE = "Visual anomalies"
        const val ANOMALIES_TOGGLE_DESC_RITUAL = "Rare visual moments."
        const val ANOMALIES_TOGGLE_DESC_UNHINGED = "Sometimes the walls move. This controls that."

        const val WHISPERS_TOGGLE = "Whispers"
        const val WHISPERS_TOGGLE_DESC_RITUAL = "Occasional messages."
        const val WHISPERS_TOGGLE_DESC_UNHINGED = "The archive has things to say. Let it."
    }

    // ═══════════════════════════════════════════════════════════════
    //  Empty States
    // ═══════════════════════════════════════════════════════════════

    object EmptyStates {
        const val EMPTY_LIBRARY_NORMAL = "Your library is empty. Add some books to get started."
        const val EMPTY_LIBRARY_RITUAL = "The shelves are bare. Add something to the collection."
        const val EMPTY_LIBRARY_UNHINGED = "The archive is empty. It wasn't always. Add something before it notices."

        const val EMPTY_DOWNLOADS_NORMAL = "No downloaded books."
        const val EMPTY_DOWNLOADS_RITUAL = "Nothing stored locally."
        const val EMPTY_DOWNLOADS_UNHINGED = "The vault is hollow. Fill it."

        const val EMPTY_BOOKMARKS_NORMAL = "No bookmarks yet."
        const val EMPTY_BOOKMARKS_RITUAL = "No places marked."
        const val EMPTY_BOOKMARKS_UNHINGED = "You've left no trail. The pages will forget you were here."

        const val EMPTY_SEARCH_NORMAL = "No results found."
        const val EMPTY_SEARCH_RITUAL = "Nothing surfaced."
        const val EMPTY_SEARCH_UNHINGED = "The archive searched. It found nothing it was willing to show you."
    }

    // ═══════════════════════════════════════════════════════════════
    //  Error States
    // ═══════════════════════════════════════════════════════════════

    object Errors {
        const val CONNECTION_ERROR_NORMAL = "Unable to connect. Check your internet connection."
        const val CONNECTION_ERROR_RITUAL = "Connection lost. Check your network."
        const val CONNECTION_ERROR_UNHINGED = "The line went quiet. Check your connection."

        const val SERVER_ERROR_NORMAL = "Something went wrong. Please try again."
        const val SERVER_ERROR_RITUAL = "A fault occurred. Try again."
        const val SERVER_ERROR_UNHINGED = "Something broke. Not your fault. Probably. Try again."

        const val PLAYBACK_ERROR_NORMAL = "Unable to play this book."
        const val PLAYBACK_ERROR_RITUAL = "Playback interrupted."
        const val PLAYBACK_ERROR_UNHINGED = "The telling stopped. It wasn't finished. Try again."

        const val FILE_NOT_FOUND_NORMAL = "This file could not be found."
        const val FILE_NOT_FOUND_RITUAL = "The file is missing."
        const val FILE_NOT_FOUND_UNHINGED = "It was here. Now it isn't. That happens sometimes."
    }
}
