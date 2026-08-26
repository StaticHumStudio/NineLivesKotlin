package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.data.remote.RemoteResult

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
)

/** One line for the bug report body. */
internal fun describeLastSync(report: SyncReport?): String {
    if (report == null) return "never"
    val outcome = if (report.failure != null) {
        "FAILED (${report.failure})"
    } else {
        "${report.libraryCount} libraries, ${report.bookCount} books"
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
        return SyncReport(0, 0, "libraries: ${libraries.reason}", ageMinutes)
    }
    val names = when (libraries) {
        is RemoteResult.Ok -> libraries.value
        is RemoteResult.Partial -> libraries.value
        is RemoteResult.Failed -> emptyList()
    }
    var books = 0
    var failure: String? = null
    items.forEachIndexed { index, result ->
        when (result) {
            is RemoteResult.Ok -> books += result.value
            is RemoteResult.Partial -> {
                books += result.value
                if (failure == null) failure = itemFailure(names, index, result.reason)
            }
            is RemoteResult.Failed -> if (failure == null) {
                failure = itemFailure(names, index, result.reason)
            }
        }
    }
    return SyncReport(names.size, books, failure, ageMinutes)
}

private fun itemFailure(names: List<String>, index: Int, reason: String): String {
    val name = names.getOrNull(index) ?: "library $index"
    return "items[$name]: $reason"
}

internal data class SyncSnapshot(val report: SyncReport, val completedAtMs: Long)

/**
 * Stamp the report with how long ago it ran. Clamped at zero because device
 * clock changes otherwise produce a negative age in a support email.
 */
internal fun SyncSnapshot?.atAge(nowMs: Long): SyncReport? = this?.report?.copy(
    ageMinutes = (nowMs - completedAtMs).coerceAtLeast(0L) / 60_000L,
)
