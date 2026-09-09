package com.ninelivesaudio.app.service.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSlotScopeTest {

    @Test
    fun `slot ignores malformed same-prefix download identity`() {
        assertFalse(
            slotDownloadRowAllowed(
                isLocal = false,
                downloadId = "prefix:broken",
                audioBookId = "prefix:book",
                decode = { id -> if (id == "prefix:book") "book" else null },
            ),
        )
    }

    @Test
    fun `slot retains local rows without a remote envelope`() {
        assertTrue(slotDownloadRowAllowed(true, "local-download", "local-book") { null })
    }
}
