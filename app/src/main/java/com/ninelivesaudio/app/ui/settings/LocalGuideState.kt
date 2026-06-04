package com.ninelivesaudio.app.ui.settings

/**
 * The local-loading guide is shown expanded on first run (no folders added yet)
 * and collapsed once the user has at least one local library.
 */
fun localGuideStartsExpanded(hasLocalLibraries: Boolean): Boolean = !hasLocalLibraries
