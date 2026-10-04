package com.ninelivesaudio.app.ui.settings

import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.service.local.LocalLibraryScanner
import com.ninelivesaudio.app.service.local.LocalScanImport
import com.ninelivesaudio.app.service.local.LocalScanRequest
import com.ninelivesaudio.app.service.local.LocalScanState
import com.ninelivesaudio.app.service.local.ScannedLocalBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder scan runs app-wide now (#57), and Settings only shows it. These
 * pin that Settings shows the same thing it did when the scan ran inside it.
 */
class LocalScanUiStateTest {

    private val library = Library(id = "folder", name = "Audiobooks", isLocal = true, folderUri = "content://tree/a")
    private val tenBooks = LocalLibraryScanner.ScanResult(
        books = (1..10).map { ScannedLocalBook(id = "b$it", title = "b$it", author = "A", tracks = emptyList()) },
        skippedCount = 0,
        errorMessages = emptyList(),
        foldersScanned = 10,
    )

    @Test
    fun `a running scan clears the last result`() {
        val before = SettingsViewModel.UiState(lastScanMessage = "9 books found", errorMessage = "old")

        val shown = before.withLocalScan(LocalScanState.Running(LocalScanRequest.Rescan(library)))

        assertTrue(shown.isScanning)
        assertNull(shown.lastScanMessage)
        assertNull(shown.errorMessage)
    }

    @Test
    fun `a finished rescan shows its count`() {
        val finished = LocalScanState.Finished(
            id = 1,
            request = LocalScanRequest.Rescan(library),
            imported = LocalScanImport(library, tenBooks),
            failure = null,
        )

        val shown = SettingsViewModel.UiState(isScanning = true).withLocalScan(finished)

        assertFalse(shown.isScanning)
        assertEquals("10 books found", shown.lastScanMessage)
        assertEquals("Rescan complete: 10 books found", shown.successMessage)
    }

    @Test
    fun `a finished Add Folder selects the new library`() {
        val finished = LocalScanState.Finished(
            id = 1,
            request = LocalScanRequest.AddFolder("content://tree/a"),
            imported = LocalScanImport(library, tenBooks),
            failure = null,
        )

        val shown = SettingsViewModel.UiState(isScanning = true, inaccessibleLocalLibraryIds = setOf("folder"))
            .withLocalScan(finished)

        assertEquals(library, shown.selectedLocalLibrary)
        assertTrue(shown.inaccessibleLocalLibraryIds.isEmpty())
        assertEquals("10 books imported", shown.successMessage)
    }

    @Test
    fun `a failed scan says which action failed`() {
        fun failed(request: LocalScanRequest) =
            LocalScanState.Finished(id = 1, request = request, imported = null, failure = "No access")

        val start = SettingsViewModel.UiState(isScanning = true)
        assertEquals("Rescan failed: No access", start.withLocalScan(failed(LocalScanRequest.Rescan(library))).errorMessage)
        assertEquals("Scan failed: No access", start.withLocalScan(failed(LocalScanRequest.AddFolder("u"))).errorMessage)
        assertFalse(start.withLocalScan(failed(LocalScanRequest.Rescan(library))).isScanning)
    }
}
