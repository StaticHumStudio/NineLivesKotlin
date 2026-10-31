package com.ninelivesaudio.app.service

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** How long a stopped book's server calls may take in total (decision J2). */
internal val TERMINAL_SEQUENCE_DEADLINE = 5.seconds

/**
 * One deadline for a stopped book's server calls: the progress push, the
 * session sync and the session close (#54). The next load of that book waits
 * on them, and each used to get its own 5 seconds. The deadline is fixed when
 * the budget is made. Once it is spent, later calls are skipped without
 * running, and the first time it runs out [onExpired] asks for a reachability
 * check so the next load sees a fresh status. It does not mark the server
 * unreachable, since a slow server is not a dead one. The local progress
 * write and its queue row land before any of this, so nothing is lost.
 */
internal class TerminalServerBudget(
    total: Duration = TERMINAL_SEQUENCE_DEADLINE,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onExpired: () -> Unit = {},
) {
    private val deadlineNanos = nanoTime() + total.inWholeNanoseconds
    private val expiredReported = AtomicBoolean(false)

    fun remaining(): Duration = (deadlineNanos - nanoTime()).coerceAtLeast(0L).nanoseconds

    /** True once the deadline has passed. Fires [onExpired] at most once. */
    fun checkExpired(): Boolean {
        val expired = remaining() == Duration.ZERO
        if (expired && expiredReported.compareAndSet(false, true)) onExpired()
        return expired
    }

    /**
     * Runs [block] within what is left of the deadline. Null when the server
     * is not live, the deadline is spent, or the call timed out. The block
     * must be cancellable, including any lock it waits on.
     */
    suspend fun <T> call(serverLive: Boolean, block: suspend () -> T): T? {
        if (!serverLive) return null
        if (checkExpired()) return null
        return terminalServerCall(serverLive = true, timeout = remaining(), call = block).also { checkExpired() }
    }
}

/** How long a stopped book's progress push may wait on the server. */
internal fun terminalPushTimeout(budget: TerminalServerBudget?): Duration =
    budget?.remaining() ?: TERMINAL_PROGRESS_PUSH_TIMEOUT
