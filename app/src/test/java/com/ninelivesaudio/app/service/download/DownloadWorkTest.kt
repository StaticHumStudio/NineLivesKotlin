package com.ninelivesaudio.app.service.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The enqueue site and the pause/cancel sites must agree on a download's work
 * tag, otherwise cancelling by tag would not stop the running worker. Pin the
 * tag format so the two never drift apart.
 */
class DownloadWorkTest {

    @Test
    fun `work tag is namespaced by download id`() {
        assertEquals("download:abc-123", downloadWorkTag("abc-123"))
    }

    @Test
    fun `distinct ids produce distinct tags`() {
        assertEquals(
            listOf("download:a", "download:b"),
            listOf(downloadWorkTag("a"), downloadWorkTag("b")),
        )
    }
}
