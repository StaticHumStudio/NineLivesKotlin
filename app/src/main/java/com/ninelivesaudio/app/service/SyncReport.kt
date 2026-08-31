package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.remote.RemoteResult
import com.ninelivesaudio.app.data.remote.describeFailure
import com.ninelivesaudio.app.data.remote.map
import com.ninelivesaudio.app.data.remote.valueOrEmpty
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.AudioBook
import com.ninelivesaudio.app.domain.model.LastSyncRecord
import com.ninelivesaudio.app.domain.model.Library
import com.ninelivesaudio.app.domain.model.SyncResult
import kotlinx.coroutines.CancellationException

/**
 * What the last server sync actually produced. Counts and failures are kept
 * apart on purpose: an empty shelf and a failed fetch used to render the same
 * way (zero books), which sent every "nothing shows up" report at the network
 * when the cause was usually library selection.
 */
internal data class SyncReport(
    val libraryCount: Int,
    val bookCount: Int,
    val failure: String? = null,
    val ageMinutes: Long? = null,
    val result: SyncResult = if (failure == null) SyncResult.SUCCESS else SyncResult.FAILED,
)

/** One line for the bug report body. */
internal fun describeLastSync(report: SyncReport?): String {
    if (report == null) return "never"
    val detail = report.failure?.let { " ($it)" }.orEmpty()
    val outcome = when (report.result) {
        SyncResult.SUCCESS -> "${report.libraryCount} libraries, ${report.bookCount} books"
        SyncResult.PARTIAL -> "INCOMPLETE$detail"
        SyncResult.FAILED -> "FAILED$detail"
    }
    val age = report.ageMinutes?.let { ", ${it}m ago" } ?: ""
    return outcome + age
}

/**
 * Fold a library-list fetch and its per-library item fetches into one report.
 * The first failure wins and is named, but the counts gathered before it are
 * kept: "2 libraries, 200 books, FAILED on Podcasts" is a far better lead than
 * a bare zero.
 */
internal fun buildSyncReport(
    libraries: RemoteResult<List<String>>,
    items: List<RemoteResult<Int>>,
    ageMinutes: Long?,
): SyncReport {
    if (libraries is RemoteResult.Failed) {
        return SyncReport(
            libraryCount = 0,
            bookCount = 0,
            failure = "libraries: ${libraries.reason}",
            ageMinutes = ageMinutes,
            result = SyncResult.FAILED,
        )
    }
    val names = when (libraries) {
        is RemoteResult.Ok -> libraries.value
        is RemoteResult.Partial -> libraries.value
        is RemoteResult.Failed -> emptyList()
    }
    var books = 0
    var failure: String? = null
    var syncResult = if (libraries is RemoteResult.Partial) SyncResult.PARTIAL else SyncResult.SUCCESS
    items.forEachIndexed { index, itemResult ->
        when (itemResult) {
            is RemoteResult.Ok -> books += itemResult.value
            is RemoteResult.Partial -> {
                books += itemResult.value
                if (failure == null) failure = itemFailure(names, index, itemResult.reason)
                if (syncResult != SyncResult.FAILED) syncResult = SyncResult.PARTIAL
            }
            is RemoteResult.Failed -> {
                if (failure == null) failure = itemFailure(names, index, itemResult.reason)
                syncResult = SyncResult.FAILED
            }
        }
    }
    return SyncReport(names.size, books, failure, ageMinutes, syncResult)
}

/**
 * Build the durable result for the Library screen's targeted refresh. The
 * screen refreshes the library list and only the selected shelf, so it must
 * not pretend that it ran the full-account sync used by [SyncManager].
 */
internal fun buildShelfSyncReport(
    libraries: RemoteResult<List<Library>>?,
    selectedLibrary: Library?,
    items: RemoteResult<List<AudioBook>>?,
): SyncReport? {
    if (libraries == null && items == null) return null

    val libraryCount = when (libraries) {
        is RemoteResult.Ok -> libraries.value.size
        is RemoteResult.Partial -> libraries.value.size
        is RemoteResult.Failed -> 0
        null -> if (selectedLibrary == null) 0 else 1
    }
    val bookCount = when (items) {
        is RemoteResult.Ok -> items.value.size
        is RemoteResult.Partial -> items.value.size
        is RemoteResult.Failed, null -> 0
    }
    val itemName = selectedLibrary?.name ?: "library"

    val (result, failure) = when {
        libraries is RemoteResult.Failed ->
            SyncResult.FAILED to "libraries: ${libraries.reason}"
        items is RemoteResult.Failed ->
            SyncResult.FAILED to "items[$itemName]: ${items.reason}"
        libraries is RemoteResult.Partial ->
            SyncResult.PARTIAL to "libraries: ${libraries.reason}"
        items is RemoteResult.Partial ->
            SyncResult.PARTIAL to "items[$itemName]: ${items.reason}"
        else -> SyncResult.SUCCESS to null
    }

    return SyncReport(
        libraryCount = libraryCount,
        bookCount = bookCount,
        failure = failure,
        result = result,
    )
}

/**
 * What syncNow() persists when the pre-flight reachability probe itself
 * fails, before the real library/item fetch ever runs. Without this, a probe
 * failure returned silently and no [LastSyncRecord] was ever written. A
 * fresh install on a live network with an unreachable server looked
 * identical to an app that had simply never synced.
 */
internal fun unreachableServerSyncReport(): SyncReport = SyncReport(
    libraryCount = 0,
    bookCount = 0,
    failure = "server unreachable",
    result = SyncResult.FAILED,
)

internal suspend fun fetchLibrarySyncReport(
    fetchLibraries: suspend () -> RemoteResult<List<Library>>,
    fetchItems: suspend (Library) -> RemoteResult<List<AudioBook>>,
): SyncReport {
    val librariesResult = try {
        fetchLibraries()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RemoteResult.Failed(describeFailure(e))
    }

    val itemResults = librariesResult.valueOrEmpty().map { library ->
        val result = try {
            fetchItems(library)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RemoteResult.Failed(describeFailure(e))
        }
        result.map { books -> books.size }
    }

    return buildSyncReport(
        libraries = librariesResult.map { libraries -> libraries.map { it.name } },
        items = itemResults,
        ageMinutes = null,
    )
}

private fun itemFailure(names: List<String>, index: Int, reason: String): String {
    val name = names.getOrNull(index) ?: "library $index"
    return "items[$name]: $reason"
}

internal data class SyncSnapshot(val report: SyncReport, val completedAtMs: Long)

internal fun AppSettings.withLastSyncIfServerUnchanged(
    report: SyncReport,
    completedAtMs: Long,
    serverUrlAtStart: String,
): AppSettings {
    if (serverUrl != serverUrlAtStart) return this
    if (lastSyncForCurrentServer()?.completedAtMs?.let { it >= completedAtMs } == true) return this
    return copy(
        lastSync = LastSyncRecord(
            result = report.result,
            libraryCount = report.libraryCount,
            bookCount = report.bookCount,
            failure = report.failure,
            completedAtMs = completedAtMs,
            serverUrl = serverUrlAtStart,
        ),
    )
}

internal fun AppSettings.lastSyncForCurrentServer(): LastSyncRecord? =
    lastSync?.takeIf { it.serverUrl == serverUrl }

/**
 * The result of one attempt to persist a sync's outcome as [LastSyncRecord].
 *
 * [recorded] is true only when the transform actually decided to write a new
 * record — i.e. the configured server had not changed underneath the sync.
 * [persisted] is false when the write to durable settings storage itself
 * failed. When that happens, [record] is still the truthful in-memory
 * answer this attempt produced (the [LastSyncRecord] the transform decided
 * on before the write failed) — it is null only when the failure happened
 * before the transform ever got to run. A failed write must never look like
 * a clean result with no signal: the caller needs [persisted] to know
 * storage didn't take, and [record] to still report what actually happened
 * rather than silently falling back to stale or absent data.
 */
internal data class PersistedSyncOutcome(
    val recorded: Boolean,
    val persisted: Boolean,
    val record: LastSyncRecord?,
)

/**
 * Persist [report] as this sync attempt's [LastSyncRecord] via [updateSettings],
 * tolerating a failure to actually write it to durable storage instead of
 * letting that exception propagate and get swallowed by a caller's generic
 * catch (which used to report a clean [SyncAttempt.RAN] with no idea the
 * record was never stored — see issue #14's adversarial review, finding 1).
 */
internal suspend fun persistSyncOutcome(
    report: SyncReport,
    completedAtMs: Long,
    serverUrlAtStart: String,
    updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit,
): PersistedSyncOutcome {
    var recorded = false
    var record: LastSyncRecord? = null
    var persisted = true
    try {
        updateSettings {
            val updated = it.withLastSyncIfServerUnchanged(report, completedAtMs, serverUrlAtStart)
            recorded = updated !== it
            if (recorded) record = updated.lastSync
            updated
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        persisted = false
    }
    return PersistedSyncOutcome(recorded, persisted, record)
}

internal fun AppSettings.syncSnapshotForCurrentServer(): SyncSnapshot? =
    lastSyncForCurrentServer()?.toSyncSnapshot()

internal fun LastSyncRecord.toSyncSnapshot(): SyncSnapshot = SyncSnapshot(
    report = SyncReport(
        libraryCount = libraryCount,
        bookCount = bookCount,
        failure = failure,
        result = result,
    ),
    completedAtMs = completedAtMs,
)

/**
 * Stamp the report with how long ago it ran. Clamped at zero because device
 * clock changes otherwise produce a negative age in a support email.
 */
internal fun SyncSnapshot?.atAge(nowMs: Long): SyncReport? = this?.report?.copy(
    ageMinutes = (nowMs - completedAtMs).coerceAtLeast(0L) / 60_000L,
)
