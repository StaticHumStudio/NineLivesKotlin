package com.ninelivesaudio.app.service.local

import com.ninelivesaudio.app.domain.model.Library
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving Settings mid-scan used to cancel the scan, so nothing imported
 * (#57). The coordinator runs the scan in its own app-wide scope and keeps
 * the result until Settings shows it.
 */
class LocalScanCoordinatorTest {

    private val library = Library(id = "folder", name = "Audiobooks", isLocal = true, folderUri = "content://tree/a")
    private val scanResult = LocalLibraryScanner.ScanResult(
        books = emptyList(),
        skippedCount = 0,
        errorMessages = emptyList(),
        foldersScanned = 1,
    )
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun coordinator(work: suspend (LocalScanRequest) -> LocalScanImport) =
        LocalScanCoordinator(appScope, work)

    private suspend fun LocalScanCoordinator.awaitFinished(): LocalScanState.Finished =
        withTimeout(5_000) { state.first { it is LocalScanState.Finished } as LocalScanState.Finished }

    @Test
    fun `a scan started from a screen finishes after that screen is gone`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var imported = false
        val coordinator = coordinator {
            release.await()
            imported = true
            LocalScanImport(library, scanResult)
        }

        // Settings' own scope starts the scan, then is cleared as the user leaves.
        val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        screenScope.launch { coordinator.start(LocalScanRequest.Rescan(library)) }.join()
        screenScope.cancel()
        release.complete(Unit)

        val finished = coordinator.awaitFinished()
        assertTrue(imported)
        assertEquals(library, finished.imported?.library)
        assertNull(finished.failure)
    }

    @Test
    fun `the scan shows as running until it ends`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val coordinator = coordinator { release.await(); LocalScanImport(library, scanResult) }
        val request = LocalScanRequest.AddFolder("content://tree/a")

        assertTrue(coordinator.start(request))
        assertEquals(LocalScanState.Running(request), coordinator.state.value)

        release.complete(Unit)
        assertEquals(request, coordinator.awaitFinished().request)
    }

    @Test
    fun `a second scan while one runs is refused`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val coordinator = coordinator { runs++; release.await(); LocalScanImport(library, scanResult) }

        assertTrue(coordinator.start(LocalScanRequest.Rescan(library)))
        assertFalse(coordinator.start(LocalScanRequest.AddFolder("content://tree/b")))

        release.complete(Unit)
        coordinator.awaitFinished()
        assertEquals(1, runs)
        assertTrue(coordinator.start(LocalScanRequest.Rescan(library)))
    }

    @Test
    fun `a failed scan reports why`() = runBlocking {
        val coordinator = coordinator { throw IllegalStateException("Permission revoked") }

        coordinator.start(LocalScanRequest.Rescan(library))

        val finished = coordinator.awaitFinished()
        assertNull(finished.imported)
        assertEquals("Permission revoked", finished.failure)
    }

    @Test
    fun `a result stays until Settings acknowledges it`() = runBlocking {
        val coordinator = coordinator { LocalScanImport(library, scanResult) }
        coordinator.start(LocalScanRequest.Rescan(library))
        val finished = coordinator.awaitFinished()

        coordinator.acknowledge(finished.id + 1)
        assertEquals(finished, coordinator.state.value)

        coordinator.acknowledge(finished.id)
        assertEquals(LocalScanState.Idle, coordinator.state.value)
    }
}
