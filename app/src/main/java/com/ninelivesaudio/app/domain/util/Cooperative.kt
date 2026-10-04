package com.ninelivesaudio.app.domain.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** How many items a long loop handles between cancellation checks. */
internal const val COOPERATIVE_CHECK_EVERY = 256

/**
 * [map] that stops when its coroutine is cancelled, checking every
 * [checkEvery] items. A plain map over tens of thousands of rows never
 * suspends, so a cancelled shelf build kept decoding to the end while the
 * newer one started beside it.
 */
internal suspend inline fun <T, R> List<T>.mapCooperatively(
    checkEvery: Int = COOPERATIVE_CHECK_EVERY,
    transform: (T) -> R,
): List<R> {
    val context = currentCoroutineContext()
    val out = ArrayList<R>(size)
    for (index in indices) {
        if (index % checkEvery == 0) context.ensureActive()
        out.add(transform(this[index]))
    }
    return out
}
