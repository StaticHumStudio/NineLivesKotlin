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

    /**
     * @param latestSeenEpochMs the persisted high-water mark of every clock
     *   reading ever observed for this trial (initialized to [startedAtEpochMs]
     *   at the moment the trial started, only ever advanced forward from
     *   there). Evaluation runs against `max(nowEpochMs, latestSeenEpochMs)`,
     *   so setting the device clock back cannot resurrect a trial that this
     *   mark already proves has expired. Pass null only when no trial has ever
     *   started, since that is the one case with no mark to defend.
     */
    fun evaluate(
        nowEpochMs: Long,
        startedAtEpochMs: Long?,
        latestSeenEpochMs: Long? = null,
    ): ActiveTrial? {
        if (nowEpochMs < 0 || startedAtEpochMs == null || startedAtEpochMs < 0) return null
        // The future-start check is deliberately against the raw clock, not the
        // watermark: it is judging whether the START timestamp itself is
        // plausible, not how much time has elapsed since.
        if (startedAtEpochMs > nowEpochMs &&
            startedAtEpochMs - nowEpochMs > FUTURE_START_TOLERANCE_MS
        ) return null
        if (startedAtEpochMs > Long.MAX_VALUE - DURATION_MS) return null

        val effectiveNow = if (latestSeenEpochMs != null) {
            maxOf(nowEpochMs, latestSeenEpochMs)
        } else {
            nowEpochMs
        }

        val endsAt = startedAtEpochMs + DURATION_MS
        if (effectiveNow >= endsAt) return null

        val remainingMs = endsAt - effectiveNow
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
