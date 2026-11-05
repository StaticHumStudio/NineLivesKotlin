package com.ninelivesaudio.app.widget.binder

import android.appwidget.AppWidgetManager
import com.ninelivesaudio.app.widget.WidgetLaunch
import com.ninelivesaudio.app.widget.actionName
import com.ninelivesaudio.app.widget.bookIdOrNull
import com.ninelivesaudio.app.widget.parseWidgetLaunch
import com.ninelivesaudio.app.widget.widgetRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PocketClickTest {
    @Test fun `an empty pocket rebound after a book taps to nothing`() {
        val book = PocketFill.TitleOnly("Dune", WidgetLaunch.PlayBook("b1"))
        assertEquals(WidgetLaunch.PlayBook("b1"), book.clickLaunch())
        assertEquals(WidgetLaunch.Nothing, PocketFill.Empty.clickLaunch())
    }

    @Test fun `the nothing launch round trips and routes nowhere`() {
        val l = WidgetLaunch.Nothing
        assertEquals(l, parseWidgetLaunch(l.actionName(), l.bookIdOrNull))
        assertNull(widgetRoute(l))
    }

    @Test fun `a page turn needs the turn extra, a real widget and a real page`() {
        assertEquals(BinderTurn(7, 3), binderTurnOf(hasTurn = true, appWidgetId = 7, page = 3))
        assertEquals(BinderTurn(7, 0), binderTurnOf(hasTurn = true, appWidgetId = 7, page = 0))
        assertNull(binderTurnOf(hasTurn = false, appWidgetId = 7, page = 3))
        assertNull(binderTurnOf(hasTurn = true, appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID, page = 3))
        assertNull(binderTurnOf(hasTurn = true, appWidgetId = 7, page = -1))
    }

    @Test fun `binder copy follows the punctuation rules`() {
        BinderCopy.all.forEach { line ->
            assertFalse(line, line.contains('—') || line.contains('–') || line.contains(';') || line.contains(" - "))
        }
    }
}
