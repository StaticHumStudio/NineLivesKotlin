package com.ninelivesaudio.app.ui.settings

/**
 * The local-loading guide is shown expanded on first run (no folders added yet)
 * and collapsed once the user has at least one local library.
 */
fun localGuideStartsExpanded(hasLocalLibraries: Boolean): Boolean = !hasLocalLibraries

/**
 * The Audiobookshelf "How it works" guide is shown expanded until the user has a
 * working server connection, then collapsed.
 */
fun serverGuideStartsExpanded(isConnected: Boolean): Boolean = !isConnected
