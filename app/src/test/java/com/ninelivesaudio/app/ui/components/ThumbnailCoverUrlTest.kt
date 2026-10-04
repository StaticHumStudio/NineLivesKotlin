package com.ninelivesaudio.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * List rows ask Audiobookshelf for a smaller cover with `width=`. Only server
 * item cover URLs change. Saved covers and anything unfamiliar pass through.
 */
class ThumbnailCoverUrlTest {

    @Test
    fun `a server cover gets a width`() {
        assertEquals(
            "https://abs.example.com/api/items/li_1/cover?width=240",
            thumbnailCoverUrl("https://abs.example.com/api/items/li_1/cover", 240),
        )
    }

    @Test
    fun `a server under a subpath still gets a width`() {
        assertEquals(
            "http://nas.local:8080/abs/api/items/li_1/cover?width=240",
            thumbnailCoverUrl("http://nas.local:8080/abs/api/items/li_1/cover", 240),
        )
    }

    @Test
    fun `an existing query string keeps its values`() {
        assertEquals(
            "https://abs.example.com/api/items/li_1/cover?ts=123&width=240",
            thumbnailCoverUrl("https://abs.example.com/api/items/li_1/cover?ts=123", 240),
        )
    }

    @Test
    fun `a URL that already names a width is left alone`() {
        val url = "https://abs.example.com/api/items/li_1/cover?width=800"

        assertEquals(url, thumbnailCoverUrl(url, 240))
    }

    @Test
    fun `saved covers on the device are untouched`() {
        val file = "file:///storage/emulated/0/Android/data/x/files/Music/Book/cover.jpg"
        val content = "content://com.android.externalstorage.documents/document/primary%3ABook%2Fcover.jpg"

        assertEquals(file, thumbnailCoverUrl(file, 240))
        assertEquals(content, thumbnailCoverUrl(content, 240))
    }

    @Test
    fun `other images and broken URLs are untouched`() {
        val other = "https://images.example.com/covers/li_1.jpg"
        val broken = "https://abs example.com/api/items/li_1/cover"

        assertEquals(other, thumbnailCoverUrl(other, 240))
        assertEquals(broken, thumbnailCoverUrl(broken, 240))
    }

    @Test
    fun `a dangling question mark does not double up`() {
        assertEquals(
            "https://abs.example.com/api/items/li_1/cover?width=240",
            thumbnailCoverUrl("https://abs.example.com/api/items/li_1/cover?", 240),
        )
    }
}
