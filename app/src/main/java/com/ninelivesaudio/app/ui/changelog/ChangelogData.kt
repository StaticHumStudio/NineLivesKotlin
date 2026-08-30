package com.ninelivesaudio.app.ui.changelog

data class ChangelogRelease(
    val version: String,
    val dateLabel: String,
    val sections: List<ChangelogSection>,
)

data class ChangelogSection(
    val header: String,
    val entries: List<String>,
)

object ChangelogData {
    const val ARCHIVE_SUBTITLE = "The Archive remembers every change."

    val releases = listOf(
        ChangelogRelease(
            version = "2.1.0",
            dateLabel = "Current",
            sections = listOf(
                ChangelogSection(
                    header = "New",
                    entries = listOf(
                        "The app is now free, with one optional unlock.",
                        "Skip silence in audiobooks.",
                        "Nine Lives can ask for a Google Play review after you have used it for a while.",
                        "Try one offline server book for free.",
                        "Download every server book with the unlock.",
                        "Keep locked choices visible before you unlock them.",
                        "Use every sorting option for free.",
                        "Use Bright and Noir themes for free.",
                        "Use the Nightwatch Dossier for 30 days for free.",
                        "Use the five-band equalizer and volume boost with the unlock.",
                        "Let the sleep timer wait when it detects that you are still moving, with the unlock.",
                        "Tap the Home connection status to reconnect after a loss.",
                    ),
                ),
                ChangelogSection(
                    header = "Improved",
                    entries = listOf(
                        "Android Auto can sign in, browse, play, and show cover art.",
                        "Downloaded books with several audio files resume properly.",
                        "Library menus open beside the control you selected.",
                        "Downloads now start reliably.",
                        "Progress and resume positions are kept more reliably.",
                        "The unlock price reappears after a connection problem.",
                    ),
                ),
                ChangelogSection(
                    header = "Fixed",
                    entries = listOf(
                        "Books in nested folders now show up when you scan.",
                        "Books split across numbered discs now appear as one book.",
                        "Disc and chapter folders with matching names now stay together as one book.",
                        "The app gives a clear reason when a download cannot start.",
                        "Switching back to Audiobookshelf reconnects automatically.",
                        "Downloaded Only turns off after you reconnect.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "2.0.1",
            dateLabel = "July 1, 2026",
            sections = listOf(
                ChangelogSection(
                    header = "New",
                    entries = listOf(
                        "Choose a different Audiobookshelf library while offline.",
                        "Downloads continue after you close the app.",
                        "Pause, resume, or cancel downloads from notifications.",
                        "Choose Bright, AMOLED, or Candlelight themes.",
                        "Keep removed local books in an archive with their cover and listening history.",
                        "Permanently delete archived local books when you no longer need them.",
                    ),
                ),
                ChangelogSection(
                    header = "Improved",
                    entries = listOf(
                        "Book covers stay available when you are offline.",
                        "The app notices an offline server faster.",
                        "Android Auto shows cover art for downloaded books.",
                        "Download progress on a book page moves more smoothly.",
                    ),
                ),
                ChangelogSection(
                    header = "Fixed",
                    entries = listOf(
                        "Local mode no longer shows books from your server.",
                        "Offline progress keeps the length of a completed chapter.",
                        "Downloaded audiobooks keep playing when the app closes.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "2.0.0",
            dateLabel = "June 10, 2026",
            sections = listOf(
                ChangelogSection(
                    header = "New",
                    entries = listOf(
                        "Listen to audiobooks stored on this device.",
                        "Choose folders that hold your local audiobooks.",
                        "Find titles, authors, and chapter details in local folders.",
                        "Play tracks from local folders in their natural order.",
                        "See chapters for local audiobooks.",
                        "Save bookmarks in local audiobooks.",
                        "Keep listening history and Dossier reports for local audiobooks.",
                        "Switch between local books and Audiobookshelf books.",
                        "See a Local status while listening to local books.",
                        "Choose Local or Audiobookshelf when you first open the app.",
                        "Follow setup guides for local folders and Audiobookshelf.",
                    ),
                ),
                ChangelogSection(
                    header = "Fixed",
                    entries = listOf(
                        "Local listening progress stays on your device.",
                        "Clearing the cache keeps your local setup.",
                        "Opening the app no longer signs you out unexpectedly.",
                        "Playback, downloads, and syncing recover more reliably after startup.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "1.0.1",
            dateLabel = "April 17, 2026",
            sections = listOf(
                ChangelogSection(
                    header = "Improved",
                    entries = listOf(
                        "The equalizer labels all five bands correctly.",
                        "Voice searches work in Android Auto.",
                    ),
                ),
                ChangelogSection(
                    header = "Fixed",
                    entries = listOf(
                        "Unsafe server certificates are rejected.",
                        "System bars display correctly on modern phones.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "1.0",
            dateLabel = "April 7, 2026",
            sections = listOf(
                ChangelogSection(
                    header = "New",
                    entries = listOf(
                        "Read open source licenses inside the app.",
                        "Review and reset the saved certificate for your server.",
                    ),
                ),
                ChangelogSection(
                    header = "Improved",
                    entries = listOf(
                        "Remember your preferred library.",
                        "Use a flatter library, downloads, and Settings layout.",
                        "Check your server connection faster.",
                        "Book covers use less memory while loading.",
                        "The equalizer shows the number of bands on your device.",
                        "The contact address is correct.",
                    ),
                ),
                ChangelogSection(
                    header = "Fixed",
                    entries = listOf(
                        "Only trusted apps can control your playback.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "0.95",
            dateLabel = "February 25, 2026",
            sections = listOf(
                ChangelogSection(
                    header = "New",
                    entries = listOf(
                        "Resume playback with an automatic rewind.",
                        "Let the sleep timer wait while you are moving.",
                        "Reset the sleep timer by shaking your phone.",
                        "Choose how far playback rewinds when the timer ends.",
                        "Refresh your server connection from Settings.",
                        "See the app version and studio link in Settings.",
                        "Send an optional crash or feedback email.",
                        "Choose from six time periods in the Nightwatch Dossier.",
                        "Skip chapters from Android Auto.",
                        "See vintage placeholders when a book has no cover.",
                        "Choose among multiple libraries and keep your selection.",
                    ),
                ),
                ChangelogSection(
                    header = "Improved",
                    entries = listOf(
                        "The Nightwatch Dossier uses the library you selected.",
                    ),
                ),
                ChangelogSection(
                    header = "Fixed",
                    entries = listOf(
                        "Android Auto shows chapter lengths and cover art.",
                        "Reports leave out private account details.",
                        "Listening reports share reliably.",
                        "Finished books recover their playback and chapter position.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "0.9",
            dateLabel = "February 23, 2026",
            sections = listOf(
                ChangelogSection(
                    header = "New",
                    entries = listOf(
                        "See book, narrator, genre, and listening time patterns in the Nightwatch Dossier.",
                        "Share a listening report as an image.",
                        "Make quiet audiobooks louder.",
                    ),
                ),
                ChangelogSection(
                    header = "Fixed",
                    entries = listOf(
                        "Starting playback no longer crashes the app.",
                        "Android Auto opens the book you selected.",
                        "The app reconnects after your phone sleeps or returns from the background.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "0.6",
            dateLabel = "February 20, 2026",
            sections = listOf(
                ChangelogSection(
                    header = "New",
                    entries = listOf(
                        "See your listening history.",
                        "Connect to servers with self-signed certificates.",
                        "Filter and group your library.",
                        "Browse and search your library in Android Auto.",
                        "See download progress in the app.",
                        "See chapter progress in playback notifications.",
                    ),
                ),
                ChangelogSection(
                    header = "Improved",
                    entries = listOf(
                        "Recently Played uses the right order.",
                        "Library lists use a more compact layout.",
                        "Book covers show a fluorescent progress border.",
                        "Book descriptions keep their formatting.",
                    ),
                ),
                ChangelogSection(
                    header = "Fixed",
                    entries = listOf(
                        "Chapter seeking stays in sync.",
                    ),
                ),
            ),
        ),
    )
}

fun shouldShowChangelogBadge(currentVersion: String, lastSeenVersion: String): Boolean =
    currentVersion.isNotBlank() && currentVersion != lastSeenVersion

/**
 * Improved and Fixed render as one block. The data keeps them separate so
 * each entry stays tagged by what it actually was, and only the presentation
 * collapses them.
 */
fun displaySections(sections: List<ChangelogSection>): List<ChangelogSection> {
    val (mergeable, keep) = sections.partition { it.header == "Improved" || it.header == "Fixed" }
    if (mergeable.size < 2) return sections
    return keep + ChangelogSection(
        header = "Improved and fixed",
        entries = mergeable.flatMap { it.entries },
    )
}
