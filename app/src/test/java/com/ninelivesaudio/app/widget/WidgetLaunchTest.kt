package com.ninelivesaudio.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetLaunchTest {

    @Test fun `every launch survives the intent extras`() {
        for (l in listOf(WidgetLaunch.OpenApp, WidgetLaunch.OpenBook("b1"), WidgetLaunch.PlayBook("b1"), WidgetLaunch.OpenUnlock, WidgetLaunch.TogglePlay("b1")))
            assertEquals(l, parseWidgetLaunch(l.actionName(), l.bookIdOrNull))
    }

    @Test fun `a book launch without a book is no launch`() {
        assertNull(parseWidgetLaunch("open_book", null))
        assertNull(parseWidgetLaunch("play_book", " "))
        assertNull(parseWidgetLaunch("toggle_play", null))
        assertNull(parseWidgetLaunch("something_else", "b1"))
        assertNull(parseWidgetLaunch(null, null))
    }

    @Test fun `opening the app works like the launcher icon and leaves the screen alone`() {
        assertNull(widgetRoute(WidgetLaunch.OpenApp))
    }

    @Test fun `open launches map to app screens and play stays in the trampoline`() {
        assertEquals(WidgetRoute.Book("b1"), widgetRoute(WidgetLaunch.OpenBook("b1")))
        assertEquals(WidgetRoute.Unlock, widgetRoute(WidgetLaunch.OpenUnlock))
        assertNull(widgetRoute(WidgetLaunch.PlayBook("b1")))
        assertNull(widgetRoute(WidgetLaunch.TogglePlay("b1")))
    }

    @Test fun `a widget tap for another book opens that book even when a book page is on top`() {
        // Single top matched the route pattern, so book B reused book A's page.
        assertEquals(true, opensNewBookPage(topRoute = "book_detail/{bookId}", topBookId = "a", bookId = "b"))
        assertEquals(false, opensNewBookPage(topRoute = "book_detail/{bookId}", topBookId = "b", bookId = "b"))
        assertEquals(true, opensNewBookPage(topRoute = "home", topBookId = null, bookId = "b"))
        assertEquals(true, opensNewBookPage(topRoute = null, topBookId = null, bookId = "b"))
    }
}
