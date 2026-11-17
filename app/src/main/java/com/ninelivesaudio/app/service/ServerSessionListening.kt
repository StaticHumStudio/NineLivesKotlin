package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
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
    private val stopping = CompletableDeferred<Unit>()

    /**
     * The book that owns this session is stopping. A stopped book's final
     * write needs the locks an in flight heartbeat holds, so this lets that
     * heartbeat give up its wait at once (#54). Fired before the terminal
     * work waits on any lock. Safe to call more than once.
     */
    fun markStopping() {
        stopping.complete(Unit)
    }

    /**
     * A playback heartbeat's server call. It waits for the server's answer
     * like any other request, however slow, so a slow server that still
     * records each heartbeat is credited once per heartbeat. It stops waiting
     * only when the book is stopping, and then reports not acknowledged, so
     * the stopped book's final sync carries that time. At worst that counts
     * one heartbeat twice at a switch, the same as the terminal deadline (J2).
     */
    suspend fun heartbeatSend(send: suspend () -> Boolean?): Boolean {
        if (stopping.isCompleted) return false
        return coroutineScope {
            val call = async { send() }
            // An answer that is already in wins over a stop that is too.
            select {
                call.onAwait { it == true }
                stopping.onAwait {
                    call.cancel()
                    false
                }
            }
        }
    }

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

    /**
     * The session's listening the server has not acknowledged, because its
     * syncs failed (the network went away with the session open), saved
     * durably by [record] as offline listening, which the offline session
     * sync delivers later (#118 final review). Once [record] returns, that
     * time counts as delivered here, so a later sync of this session that
     * lands sends only listening after it and nothing is counted twice. A
     * [record] that throws keeps the time for the next sync or bank.
     * Returns the seconds banked.
     */
    suspend fun bank(cumulativeSeconds: Double, record: suspend (Double) -> Unit): Double =
        deliveryMutex.withLock {
            withContext(NonCancellable) {
                val delta = (cumulativeSeconds - acknowledgedSeconds).coerceAtLeast(0.0)
                if (delta > 0.0) {
                    record(delta)
                    acknowledgedSeconds = maxOf(acknowledgedSeconds, cumulativeSeconds)
                }
                delta
            }
        }

    suspend fun close(closeRemote: suspend () -> Unit) = deliveryMutex.withLock {
        if (!closed) {
            closed = true
            closeRemote()
        }
    }
}
