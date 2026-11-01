package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.ui.home.canShowHomeBooks
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetAccessTest {

    private val server = AppSettings(
        appMode = AppMode.AUDIOBOOKSHELF,
        serverUrl = "http://10.0.2.2:8080",
        selectedLibraryId = "lib_s",
        selectedLocalLibraryId = "lib_l",
    )
    private val local = server.copy(appMode = AppMode.LOCAL)

    @Test fun `a free install is locked whatever else is true`() {
        assertEquals(WidgetAccess.Locked, widgetAccess(isUnlocked = false, settings = server, hasAuthToken = true))
        assertEquals(WidgetAccess.Locked, widgetAccess(isUnlocked = false, settings = local, hasAuthToken = false))
    }

    @Test fun `server mode without a session is signed out`() {
        assertEquals(WidgetAccess.SignedOut, widgetAccess(true, server, hasAuthToken = false))
        assertEquals(WidgetAccess.SignedOut, widgetAccess(true, server.copy(serverUrl = ""), hasAuthToken = true))
    }

    @Test fun `local mode never reads the server library`() {
        assertEquals(WidgetAccess.Library("lib_l", isLocalMode = true), widgetAccess(true, local, hasAuthToken = false))
    }

    @Test fun `no chosen library has nothing to show`() {
        assertEquals(WidgetAccess.NoLibrary(false), widgetAccess(true, server.copy(selectedLibraryId = null), true))
        assertEquals(WidgetAccess.NoLibrary(true), widgetAccess(true, local.copy(selectedLocalLibraryId = null), false))
    }

    @Test fun `an unlocked widget shows a library exactly when Home would`() {
        for (s in listOf(server, local, server.copy(selectedLibraryId = null), local.copy(selectedLocalLibraryId = null)))
            for (token in listOf(true, false))
                assertEquals(canShowHomeBooks(s, token), widgetAccess(true, s, token) is WidgetAccess.Library)
    }

    @Test fun `content order puts the lock and sign in ahead of any data`() {
        assertEquals(WidgetContent.LOCKED, widgetContent(WidgetAccess.Locked, folderAccessible = true, bookCount = 5, playedCount = 5, needsPlayed = true))
        assertEquals(WidgetContent.SIGNED_OUT, widgetContent(WidgetAccess.SignedOut, true, 5, 5, true))
    }

    @Test fun `a lost folder is its own state in local mode only`() {
        assertEquals(WidgetContent.FOLDER_LOST, widgetContent(WidgetAccess.Library("lib_l", true), folderAccessible = false, bookCount = 4, playedCount = 2, needsPlayed = false))
        assertEquals(WidgetContent.READY, widgetContent(WidgetAccess.Library("lib_s", false), folderAccessible = false, bookCount = 4, playedCount = 2, needsPlayed = false))
    }

    @Test fun `the binder needs books, the large widget needs a played book`() {
        val lib = WidgetAccess.Library("lib_s", false)
        assertEquals(WidgetContent.NO_BOOKS, widgetContent(lib, true, bookCount = 0, playedCount = 0, needsPlayed = false))
        assertEquals(WidgetContent.READY, widgetContent(lib, true, bookCount = 3, playedCount = 0, needsPlayed = false))
        assertEquals(WidgetContent.NOTHING_PLAYED, widgetContent(lib, true, bookCount = 3, playedCount = 0, needsPlayed = true))
    }
}
