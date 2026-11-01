package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.service.artworkVersionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetCoversTest {

    @Test fun `a binder spread stays inside the bitmap budget`() {
        assertTrue(binderSpreadBitmapBytes() <= WIDGET_BITMAP_BUDGET_BYTES)
        assertEquals(2 * 192 * 192 * 2, binderSpreadBitmapBytes())
    }

    @Test fun `cache names change with the cover version and the size`() {
        val a = artworkVersionKey("b1", "http://h/api/items/b1/cover?ts=1", null, null)
        val b = artworkVersionKey("b1", "http://h/api/items/b1/cover?ts=2", null, null)
        assertNotEquals(widgetCoverFileName(a, 192), widgetCoverFileName(b, 192))
        assertNotEquals(widgetCoverFileName(a, 192), widgetCoverFileName(a, 96))
        assertEquals(widgetCoverFileName(a, 192), widgetCoverFileName(a, 192))
        assertTrue(widgetCoverFileName(a, 192).matches(Regex("[0-9a-f]{40}_192\\.jpg")))
    }

    @Test fun `a picked cover gets its own cache name`() {
        val scanned = artworkVersionKey("b1", "content://scan/cover.jpg", null, null)
        val picked = artworkVersionKey("b1", "content://scan/cover.jpg", "file:///data/covers/b1.jpg", 1234L)
        assertNotEquals(widgetCoverFileName(scanned, 192), widgetCoverFileName(picked, 192))
    }

    @Test fun `pruning drops the oldest unused covers first and keeps what is shown`() {
        val files = (1..5).map { "f$it.jpg" to it.toLong() }
        assertEquals(listOf("f1.jpg", "f3.jpg"), coversToPrune(files, keep = setOf("f2.jpg"), cap = 3))
        assertEquals(emptyList<String>(), coversToPrune(files, keep = emptySet(), cap = 10))
    }
}
