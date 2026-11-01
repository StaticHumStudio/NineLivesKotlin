package com.ninelivesaudio.app.widget.binder

import com.ninelivesaudio.app.widget.WidgetLaunch
import com.ninelivesaudio.app.widget.parseWidgetLaunch
import com.ninelivesaudio.app.widget.actionName
import com.ninelivesaudio.app.widget.bookIdOrNull
import com.ninelivesaudio.app.widget.widgetRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleeveClickTest {
    @Test fun `an empty sleeve rebound after a book taps to nothing`() {
        val book = SleeveFill.TitleOnly("Dune", WidgetLaunch.PlayBook("b1"))
        assertEquals(WidgetLaunch.PlayBook("b1"), book.clickLaunch())
        assertEquals(WidgetLaunch.Nothing, SleeveFill.Empty.clickLaunch())
    }

    @Test fun `the icon sleeve still opens the app`() {
        assertEquals(WidgetLaunch.OpenApp, SleeveFill.Icon.clickLaunch())
    }

    @Test fun `the nothing launch round trips and routes nowhere`() {
        val l = WidgetLaunch.Nothing
        assertEquals(l, parseWidgetLaunch(l.actionName(), l.bookIdOrNull))
        assertNull(widgetRoute(l))
    }
}
