package com.ninelivesaudio.app.service.local

import com.ninelivesaudio.app.domain.model.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** A folder scan the user started from Settings. */
sealed interface LocalScanRequest {
    /** Add Folder: scan a newly picked folder and create its library. */
    data class AddFolder(val folderUri: String) : LocalScanRequest

    /** Rescan: scan an existing library's folder again. */
    data class Rescan(val library: Library) : LocalScanRequest
}

/** What a finished scan imported into [library]. */
data class LocalScanImport(
    val library: Library,
    val scanResult: LocalLibraryScanner.ScanResult,
)

sealed interface LocalScanState {
    data object Idle : LocalScanState

    data class Running(val request: LocalScanRequest) : LocalScanState

    /**
     * A scan ended. Exactly one of [imported] and [failure] is set. It stays
     * here until Settings shows it and calls [LocalScanCoordinator.acknowledge],
     * so a scan that finished while Settings was closed still reports when
     * Settings opens again.
     */
    data class Finished(
        val id: Long,
        val request: LocalScanRequest,
        val imported: LocalScanImport?,
        val failure: String?,
    ) : LocalScanState
}

/**
 * Owns Add Folder and Rescan for the whole app (issue #57). They used to run
 * in the Settings screen's own scope, so leaving Settings mid-scan cancelled
 * the scan and nothing was imported. This scope lives as long as the app, so
 * the scan finishes wherever the user goes, and Settings just watches [state].
 *
 * One scan at a time. Settings disables its buttons while one runs, and a
 * second request that slips through is refused rather than racing the first
 * one's reconciliation.
 */
@Singleton
class LocalScanCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val work: suspend (LocalScanRequest) -> LocalScanImport,
) {
    @Inject
    constructor(importer: LocalScanImporter) : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        work = importer::run,
    )

    private val _state = MutableStateFlow<LocalScanState>(LocalScanState.Idle)
    val state: StateFlow<LocalScanState> = _state.asStateFlow()

    private val finishedIds = AtomicLong()

    /** Starts [request]. Returns false if a scan is already running. */
    fun start(request: LocalScanRequest): Boolean {
        val running = LocalScanState.Running(request)
        while (true) {
            val current = _state.value
            if (current is LocalScanState.Running) return false
            if (_state.compareAndSet(current, running)) break
        }
        scope.launch {
            val finished = try {
                val imported = work(request)
                LocalScanState.Finished(finishedIds.incrementAndGet(), request, imported, failure = null)
            } catch (e: Exception) {
                LocalScanState.Finished(
                    finishedIds.incrementAndGet(),
                    request,
                    imported = null,
                    failure = e.message ?: e.javaClass.simpleName,
                )
            }
            _state.value = finished
        }
        return true
    }

    /** Settings showed the result of scan [id], so it can go back to Idle. */
    fun acknowledge(id: Long) {
        val current = _state.value
        if (current is LocalScanState.Finished && current.id == id) {
            _state.compareAndSet(current, LocalScanState.Idle)
        }
    }
}
