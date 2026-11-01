package com.ninelivesaudio.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetLaunchTest {

    @Test fun `every launch survives the intent extras`() {
        for (l in listOf(WidgetLaunch.OpenApp, WidgetLaunch.OpenBook("b1"), WidgetLaunch.PlayBook("b1"), WidgetLaunch.OpenUnlock))
            assertEquals(l, parseWidgetLaunch(l.actionName(), l.bookIdOrNull))
    }

    @Test fun `a book launch without a book is no launch`() {
        assertNull(parseWidgetLaunch("open_book", null))
        assertNull(parseWidgetLaunch("play_book", " "))
        assertNull(parseWidgetLaunch("something_else", "b1"))
        assertNull(parseWidgetLaunch(null, null))
    }

    @Test fun `open launches map to app screens and play stays in the trampoline`() {
        assertEquals(WidgetRoute.Home, widgetRoute(WidgetLaunch.OpenApp))
        assertEquals(WidgetRoute.Book("b1"), widgetRoute(WidgetLaunch.OpenBook("b1")))
        assertEquals(WidgetRoute.Unlock, widgetRoute(WidgetLaunch.OpenUnlock))
        assertNull(widgetRoute(WidgetLaunch.PlayBook("b1")))
    }
}
