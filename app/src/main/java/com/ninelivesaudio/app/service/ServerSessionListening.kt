package com.ninelivesaudio.app.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One instance per server session, retained by immutable progress snapshots. */
internal class ServerSessionListening {
    // Clock updates and snapshot capture are protected by PlaybackManager.sessionLock.
    var observedSeconds: Double = 0.0
    private val deliveryMutex = Mutex()
    private var acknowledgedSeconds = 0.0
    private var closed = false

    suspend fun deliver(cumulativeSeconds: Double, send: suspend (Double) -> Boolean): Boolean =
        deliveryMutex.withLock {
            if (closed) return@withLock false
            // A pause cancels the heartbeat coroutine. Finish an already-started
            // request and record its acknowledgement before allowing the pause send.
            withContext(NonCancellable) {
                val delta = (cumulativeSeconds - acknowledgedSeconds).coerceAtLeast(0.0)
                send(delta).also { accepted ->
                    if (accepted) acknowledgedSeconds = maxOf(acknowledgedSeconds, cumulativeSeconds)
                }
            }
        }

    suspend fun close(closeRemote: suspend () -> Unit) = deliveryMutex.withLock {
        if (!closed) {
            closed = true
            closeRemote()
        }
    }
}
