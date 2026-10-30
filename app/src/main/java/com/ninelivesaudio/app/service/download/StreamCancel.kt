package com.ninelivesaudio.app.service.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Run [block], a blocking read from [resource], so that cancelling the caller
 * ends it at once (#86).
 *
 * A blocking read ignores coroutine cancellation. On a server that stalls
 * mid-file it sits until the read timeout (60 seconds), and a replacement
 * drain waits behind it that long. Here a cancel closes [resource] straight
 * away, the read fails on the closed resource, and the call ends with the
 * caller's CancellationException rather than the IOException the close caused.
 * A read that ends on its own, or fails on its own, leaves [resource] alone for
 * its owner.
 *
 * The close runs on another thread of the caller's dispatcher, so the caller
 * must run on a dispatcher with a spare thread (Dispatchers.IO).
 *
 * For an OkHttp download, [resource] has to cancel the call. Closing the
 * response body from another thread does not free a read blocked on the
 * socket (OkHttp 4.12 refuses it with "Unbalanced enter/exit").
 */
internal suspend fun <T> closeOnCancel(resource: Closeable, block: suspend () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) runCatching { resource.close() }
        }
    }
    try {
        block()
    } catch (e: IOException) {
        // A read that failed because the cancel closed it reports the cancel.
        ensureActive()
        throw e
    } finally {
        finished.set(true)
        closer.cancel()
    }
}
