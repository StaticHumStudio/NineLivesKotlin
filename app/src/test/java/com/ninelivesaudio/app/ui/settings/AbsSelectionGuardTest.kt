package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.domain.model.AppMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Audiobookshelf library selector may only persist the app-wide
 * selectedLibraryId in Audiobookshelf mode. In LOCAL mode that value points at
 * a local library the ABS selector can't see, so persisting its server-library
 * fallback would hijack Home/Library to server books.
 */
class AbsSelectionGuardTest {

    @Test
    fun `persists in ABS mode when the selection changed`() {
        assertTrue(shouldPersistAbsSelection(AppMode.AUDIOBOOKSHELF, "abs-2", "abs-1"))
    }

    @Test
    fun `does not persist in ABS mode when unchanged`() {
        assertFalse(shouldPersistAbsSelection(AppMode.AUDIOBOOKSHELF, "abs-1", "abs-1"))
    }

    @Test
    fun `never persists in LOCAL mode`() {
        // savedId is a local id; the ABS fallback is a server id. Persisting it
        // is exactly the bug — must be blocked.
        assertFalse(shouldPersistAbsSelection(AppMode.LOCAL, "abs-server", "local_library_x"))
    }

    @Test
    fun `does not persist a null selection`() {
        assertFalse(shouldPersistAbsSelection(AppMode.AUDIOBOOKSHELF, null, "abs-1"))
    }
}
