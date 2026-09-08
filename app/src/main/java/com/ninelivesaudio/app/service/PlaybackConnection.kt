package com.ninelivesaudio.app.service

import java.util.concurrent.Future

/** Called on Main, alongside the completion listener and connection teardown. */
internal fun <T> retainPlaybackConnection(
    controller: T?,
    future: Future<T>?,
    isConnected: (T) -> Boolean,
    adopt: (T) -> Unit,
): Boolean {
    if (controller != null && isConnected(controller)) return true
    if (future != null && !future.isDone) return true
    // Completion can precede its queued Main callback. Adopt that successful
    // connection now instead of releasing the service's only controller.
    val completed = try {
        future?.get()
    } catch (_: Exception) {
        null
    }
    if (completed != null && isConnected(completed)) {
        adopt(completed)
        return true
    }
    return false
}
