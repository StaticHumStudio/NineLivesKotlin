package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings

/** What a home screen widget is allowed to show right now. */
internal sealed interface WidgetAccess {
    /** Free install. Generic art only, no reads of books, covers, progress or rank. */
    data object Locked : WidgetAccess

    /** Server mode without a session. */
    data object SignedOut : WidgetAccess

    /** No library chosen in the current mode. */
    data class NoLibrary(val isLocalMode: Boolean) : WidgetAccess

    /** The one library a widget may read, keyed by the current mode. */
    data class Library(val libraryId: String, val isLocalMode: Boolean) : WidgetAccess
}

/**
 * Decides what a widget may show. The lock check comes first so a free
 * user's widget never touches the data pipeline (design, "Calls settled").
 * Server credentials are never consulted in local mode, and the library is
 * always the current mode's own, so a widget never shows another mode's or
 * account's books. Agrees with Home's [com.ninelivesaudio.app.ui.home.canShowHomeBooks].
 */
internal fun widgetAccess(
    isUnlocked: Boolean,
    settings: AppSettings,
    hasAuthToken: Boolean,
): WidgetAccess {
    if (!isUnlocked) return WidgetAccess.Locked
    return when (settings.appMode) {
        AppMode.AUDIOBOOKSHELF -> when {
            !hasAuthToken || settings.serverUrl.isBlank() -> WidgetAccess.SignedOut
            else -> settings.selectedLibraryId
                ?.let { WidgetAccess.Library(it, isLocalMode = false) }
                ?: WidgetAccess.NoLibrary(isLocalMode = false)
        }
        AppMode.LOCAL -> settings.selectedLocalLibraryId
            ?.let { WidgetAccess.Library(it, isLocalMode = true) }
            ?: WidgetAccess.NoLibrary(isLocalMode = true)
    }
}

/** Which face a widget shows. */
internal enum class WidgetContent { LOCKED, SIGNED_OUT, FOLDER_LOST, NO_BOOKS, NOTHING_PLAYED, READY }

/**
 * Orders the widget states: lock, sign in, then library problems, then data.
 * [folderAccessible] only matters for a local library. [needsPlayed] is true
 * for a widget that has nothing to show until a book has progress.
 */
internal fun widgetContent(
    access: WidgetAccess,
    folderAccessible: Boolean,
    bookCount: Int,
    playedCount: Int,
    needsPlayed: Boolean,
): WidgetContent = when (access) {
    WidgetAccess.Locked -> WidgetContent.LOCKED
    WidgetAccess.SignedOut -> WidgetContent.SIGNED_OUT
    is WidgetAccess.NoLibrary -> WidgetContent.NO_BOOKS
    is WidgetAccess.Library -> when {
        access.isLocalMode && !folderAccessible -> WidgetContent.FOLDER_LOST
        bookCount == 0 -> WidgetContent.NO_BOOKS
        needsPlayed && playedCount == 0 -> WidgetContent.NOTHING_PLAYED
        else -> WidgetContent.READY
    }
}
