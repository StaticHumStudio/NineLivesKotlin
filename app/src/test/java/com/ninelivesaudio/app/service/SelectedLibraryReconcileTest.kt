package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * In LOCAL mode the app-wide selectedLibraryId must track the selected local
 * library. reconciledSelectedLibraryId returns the id to heal to, or null when
 * nothing needs changing.
 */
class SelectedLibraryReconcileTest {

    @Test
    fun `heals a stale server id in local mode`() {
        // The bug's state: local mode, but selectedLibraryId is a server id.
        assertEquals(
            "local_library_x",
            reconciledSelectedLibraryId(AppMode.LOCAL, "abs-server", "local_library_x"),
        )
    }

    @Test
    fun `no change when already consistent in local mode`() {
        assertNull(reconciledSelectedLibraryId(AppMode.LOCAL, "local_library_x", "local_library_x"))
    }

    @Test
    fun `no change when no local library is selected`() {
        assertNull(reconciledSelectedLibraryId(AppMode.LOCAL, "abs-server", null))
    }

    @Test
    fun `never touches selection in ABS mode`() {
        assertNull(reconciledSelectedLibraryId(AppMode.AUDIOBOOKSHELF, "abs-1", "local_library_x"))
    }
}
