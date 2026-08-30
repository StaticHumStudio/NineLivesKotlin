package com.ninelivesaudio.app.entitlement

import java.util.concurrent.TimeUnit

data class ActiveTrial(
    val endsAtEpochMs: Long,
    val daysRemaining: Int,
)

/** Pure wall-clock policy for the one-time unlock trial. */
object TrialPolicy {
    val DURATION_MS: Long = TimeUnit.DAYS.toMillis(14)
    private val DAY_MS: Long = TimeUnit.DAYS.toMillis(1)
    private val FUTURE_START_TOLERANCE_MS: Long = TimeUnit.MINUTES.toMillis(5)

    fun evaluate(nowEpochMs: Long, startedAtEpochMs: Long?): ActiveTrial? {
        if (nowEpochMs < 0 || startedAtEpochMs == null || startedAtEpochMs < 0) return null
        if (startedAtEpochMs > nowEpochMs &&
            startedAtEpochMs - nowEpochMs > FUTURE_START_TOLERANCE_MS
        ) return null
        if (startedAtEpochMs > Long.MAX_VALUE - DURATION_MS) return null

        val endsAt = startedAtEpochMs + DURATION_MS
        if (nowEpochMs >= endsAt) return null

        val remainingMs = endsAt - nowEpochMs
        val roundedDays = ((remainingMs - 1) / DAY_MS + 1)
            .coerceAtMost(14L)
            .toInt()

        // Allow only enough rollback for ordinary clock jitter, and keep the
        // user-facing count honest to the advertised maximum.
        return ActiveTrial(
            endsAtEpochMs = endsAt,
            daysRemaining = roundedDays,
        )
    }
}
