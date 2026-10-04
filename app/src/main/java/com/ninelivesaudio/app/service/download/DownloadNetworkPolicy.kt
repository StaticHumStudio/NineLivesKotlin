package com.ninelivesaudio.app.service.download

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import com.ninelivesaudio.app.domain.model.DownloadStatus

// ─── "Download on Wi-Fi only" (#79, decisions are unit-testable) ──────────
//
// One drain worker runs the whole queue, so the network rule lives on that
// worker. With the setting on it waits for an unmetered network. A book the
// user sends over mobile data runs the drain on any connection instead, and
// that drain takes only the books sent that way. Whatever is left behind
// gets a follow-up drain that waits for Wi-Fi.

/** Input key: true when the drain was enqueued to wait for an unmetered network. */
internal const val KEY_DRAIN_UNMETERED = "drain_unmetered"

/** Copy for the Wi-Fi only setting and the downloads waiting on it. */
object WifiOnlyCopy {
    const val SETTING_TITLE = "Download on Wi-Fi only"
    const val SETTING_SUBTITLE =
        "Books wait for Wi-Fi before they download. Tap Use mobile data in Downloads to skip the wait."
    const val WAITING_STATUS = "Waiting for Wi-Fi"
    const val USE_MOBILE_DATA = "Use mobile data"
    const val QUEUED_NOTICE = "Waiting for Wi-Fi. Tap Use mobile data in Downloads to start it now."
}

/**
 * Whether the next drain should wait for an unmetered network: only with the
 * setting on and no book queued that the user sent over mobile data.
 */
internal fun drainWaitsForUnmetered(wifiOnly: Boolean, overrideQueued: Boolean): Boolean =
    wifiOnly && !overrideQueued

/**
 * The drain worker request. [waitForUnmetered] picks the network constraint
 * and rides along as input, so the worker knows which kind of drain it is.
 * A request from before this setting carries no input and reads as a drain
 * on any connection, which then takes only overridden books.
 */
internal fun drainRequest(waitForUnmetered: Boolean): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<DownloadQueueWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(if (waitForUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
        )
        .setInputData(workDataOf(KEY_DRAIN_UNMETERED to waitForUnmetered))
        .build()

/**
 * How to enqueue the drain. A replace stays a replace. Adding work joins a
 * drain that is running in this process (it picks new rows up on its next
 * loop), but replaces an idle one, which may be sitting on the old network
 * rule waiting for Wi-Fi that a mobile data override no longer needs.
 */
internal fun drainWorkPolicy(replace: Boolean, drainRunning: Boolean): ExistingWorkPolicy =
    if (replace || !drainRunning) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP

/**
 * Whether a drain may start this book. A drain on Wi-Fi takes everything, and
 * so does any drain with the setting off. A drain on any connection with the
 * setting on takes only books the user sent over mobile data.
 */
internal fun mayDownloadOnDrain(wifiOnly: Boolean, drainUnmetered: Boolean, overridden: Boolean): Boolean =
    !wifiOnly || drainUnmetered || overridden

/**
 * Whether flipping the setting has to restart the drain.
 *
 * With no drain running, always: the queue gets re-enqueued under the new
 * rule (a drain waiting for Wi-Fi starts at once once the setting is off).
 * Turning the setting on stops a drain running on any connection, so a book
 * already streaming over mobile data stops and waits, unless it is a book the
 * user sent over mobile data on purpose. Turning it off swaps a drain bound to
 * Wi-Fi for one on any connection (the book in flight restarts its current
 * file), or losing Wi-Fi later would still park the queue. A drain already
 * on any connection carries on, since it now takes every book.
 */
internal fun wifiRuleChangeRestartsDrain(
    wifiOnly: Boolean,
    drainRunning: Boolean,
    drainUnmetered: Boolean,
    engineRowOverridden: Boolean,
): Boolean = when {
    !drainRunning -> true
    !wifiOnly -> drainUnmetered
    drainUnmetered -> false
    else -> !engineRowOverridden
}

/**
 * Whether sending a book over mobile data has to restart the drain. A drain
 * running on any connection picks it up on its next loop. Anything else (no
 * drain, or one bound to Wi-Fi that is about to be stopped for losing it)
 * would leave the book waiting, so the drain is replaced.
 */
internal fun overrideRestartsDrain(drainRunning: Boolean, drainUnmetered: Boolean): Boolean =
    !drainRunning || drainUnmetered

/** The overrides whose download row still exists, so the stored set cannot grow forever. */
internal fun liveOverrides(overrides: Set<String>, liveRowIds: Collection<String>): Set<String> {
    val live = liveRowIds.toHashSet()
    return overrides.filterTo(HashSet()) { it in live }
}

/**
 * Whether a download is held back by the setting right now, so the screen
 * says "Waiting for Wi-Fi" instead of a Queued row or a frozen progress bar.
 * A Downloading row counts too: it is what a book interrupted by losing Wi-Fi
 * looks like until the drain picks it up again.
 */
internal fun isWaitingForWifi(status: DownloadStatus, wifiOnly: Boolean, metered: Boolean, overridden: Boolean): Boolean =
    wifiOnly && metered && !overridden &&
        (status == DownloadStatus.Queued || status == DownloadStatus.Downloading)
