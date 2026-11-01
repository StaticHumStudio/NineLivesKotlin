package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.ui.home.HomeEmptyKind
import com.ninelivesaudio.app.ui.home.homeEmptyCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WidgetCopyTest {

    @Test fun `every non ready state has a calm line`() {
        for (c in WidgetContent.entries - WidgetContent.READY - WidgetContent.LOCKED)
            for (local in listOf(true, false)) assertFalse(WidgetCopy.emptyLine(c, local).isNullOrBlank())
    }

    @Test fun `empty library copy matches Home's approved lines`() {
        assertEquals(homeEmptyCopy(HomeEmptyKind.SERVER_NO_BOOKS).subtitle, WidgetCopy.emptyLine(WidgetContent.NO_BOOKS, isLocalMode = false))
        assertEquals(homeEmptyCopy(HomeEmptyKind.LOCAL_NO_BOOKS).subtitle, WidgetCopy.emptyLine(WidgetContent.NO_BOOKS, isLocalMode = true))
    }

    @Test fun `widget copy follows the punctuation rules`() {
        WidgetCopy.all.forEach { line ->
            assertFalse(line, line.contains('—') || line.contains('–') || line.contains(';') || line.contains(" - "))
        }
    }
}
