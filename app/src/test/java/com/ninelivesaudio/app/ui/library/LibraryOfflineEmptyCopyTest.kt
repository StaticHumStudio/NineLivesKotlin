package com.ninelivesaudio.app.ui.library

import com.ninelivesaudio.app.domain.model.LastSyncRecord
import com.ninelivesaudio.app.domain.model.SyncResult
import com.ninelivesaudio.app.service.ConnectivityMonitor.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOfflineEmptyCopyTest {

    private fun state(
        downloadedOnly: Boolean = true,
        total: Int = 9,
        status: ConnectionStatus = ConnectionStatus.SERVER_UNREACHABLE,
    ) = LibraryViewModel.UiState(
        showDownloadedOnly = downloadedOnly,
        totalBookCount = total,
        connectionStatus = status,
    )

    @Test
    fun `offline downloaded-only with saved books says nothing is downloaded`() {
        assertTrue(isOfflineDownloadedOnlyEmpty(state()))
        assertTrue(isOfflineDownloadedOnlyEmpty(state(status = ConnectionStatus.OFFLINE)))
    }

    @Test
    fun `connected, unfiltered, or truly empty shelves keep their old copy`() {
        assertFalse(isOfflineDownloadedOnlyEmpty(state(status = ConnectionStatus.CONNECTED)))
        assertFalse(isOfflineDownloadedOnlyEmpty(state(status = ConnectionStatus.SYNCING)))
        assertFalse(isOfflineDownloadedOnlyEmpty(state(downloadedOnly = false)))
        assertFalse(isOfflineDownloadedOnlyEmpty(state(total = 0)))
    }

    @Test
    fun `another filter hiding the downloads keeps the filter copy`() {
        assertFalse(isOfflineDownloadedOnlyEmpty(state().copy(hideFinished = true)))
        assertFalse(isOfflineDownloadedOnlyEmpty(state().copy(selectedTab = LibraryTab.InProgress)))
        assertFalse(isOfflineDownloadedOnlyEmpty(state().copy(selectedTab = LibraryTab.Completed)))
        assertFalse(isOfflineDownloadedOnlyEmpty(state().copy(selectedGroupFilter = "Dune")))
        assertFalse(isOfflineDownloadedOnlyEmpty(state().copy(searchQuery = "dune")))
    }

    @Test
    fun `the Downloaded tab and plain grouping still say nothing is downloaded`() {
        assertTrue(isOfflineDownloadedOnlyEmpty(state().copy(selectedTab = LibraryTab.Downloaded)))
        assertTrue(isOfflineDownloadedOnlyEmpty(state().copy(viewMode = ViewMode.SERIES)))
        assertTrue(isOfflineDownloadedOnlyEmpty(state().copy(searchQuery = "  ")))
    }

    @Test
    fun `banner only claims saved books when the shelf shows some`() {
        assertEquals("Last sync failed. Showing saved books.", syncWarningMessage(SyncResult.FAILED, true))
        assertEquals("Last sync failed.", syncWarningMessage(SyncResult.FAILED, false))
        assertEquals("Last sync was incomplete.", syncWarningMessage(SyncResult.PARTIAL, false))
        assertNull(syncWarningMessage(SyncResult.SUCCESS, true))
    }

    private fun record(seq: Long, result: SyncResult) = LastSyncRecord(
        result = result, libraryCount = 1, bookCount = 9, completedAtMs = 0L, outcomeSequence = seq,
    )

    @Test
    fun `a new successful or partial background sync re-queries the shelf`() {
        assertTrue(shouldRequeryShelfAfterSync(4L, record(5L, SyncResult.SUCCESS)))
        assertTrue(shouldRequeryShelfAfterSync(4L, record(5L, SyncResult.PARTIAL)))
    }

    @Test
    fun `failed, repeated, or first-seen records do not re-query`() {
        assertFalse(shouldRequeryShelfAfterSync(4L, record(5L, SyncResult.FAILED)))
        assertFalse(shouldRequeryShelfAfterSync(5L, record(5L, SyncResult.SUCCESS)))
        assertFalse(shouldRequeryShelfAfterSync(null, record(5L, SyncResult.SUCCESS)))
        assertFalse(shouldRequeryShelfAfterSync(4L, null))
    }
}
