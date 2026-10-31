package com.ninelivesaudio.app.entitlement

import com.android.billingclient.api.BillingClient.BillingResponseCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** How long a failed acknowledgement waits before each retry: 5 s, 30 s, 2 min. */
internal val ACK_RETRY_DELAYS_MS: List<Long> = listOf(5_000L, 30_000L, 120_000L)

/**
 * Whether a Play answer to an acknowledge call is worth trying again. A call that threw or timed
 * out ([responseCode] null) and a Play side or network hiccup are. Everything else is Play's
 * final word (already owned, not owned, a developer error) and a retry cannot change it.
 */
internal fun isRetryableAckResult(responseCode: Int?): Boolean = when (responseCode) {
    null,
    BillingResponseCode.SERVICE_DISCONNECTED,
    BillingResponseCode.SERVICE_UNAVAILABLE,
    BillingResponseCode.ERROR,
    BillingResponseCode.NETWORK_ERROR,
    -> true
    else -> false
}

/**
 * Calls [acknowledgeOnce] and tries again after each of [delaysMs] while the answer is retryable.
 * Returns the last response code. Cancellation propagates and is never retried.
 */
internal suspend fun acknowledgeWithRetry(
    acknowledgeOnce: suspend () -> Int?,
    delaysMs: List<Long> = ACK_RETRY_DELAYS_MS,
    sleep: suspend (Long) -> Unit = { delay(it) },
): Int? {
    var attempt = 0
    while (true) {
        val code = try {
            acknowledgeOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (!isRetryableAckResult(code) || attempt >= delaysMs.size) return code
        sleep(delaysMs[attempt])
        attempt++
    }
}

/**
 * Acknowledges purchases on its own scope, one retry chain per token, so a slow or failing
 * acknowledgement never holds the refresh lock or counts against the refresh timeout. Play
 * refunds a purchase that stays unacknowledged for three days, which is why one failed attempt
 * is not good enough.
 */
internal class PurchaseAcknowledger(
    private val scope: CoroutineScope,
    private val acknowledgeOnce: suspend (purchaseToken: String) -> Int?,
    private val delaysMs: List<Long> = ACK_RETRY_DELAYS_MS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Starts a chain for each token not already in flight. Returns only the jobs it started. */
    fun acknowledge(purchaseTokens: List<String>): List<Job> =
        purchaseTokens.distinct().filter { inFlight.add(it) }.map { token ->
            scope.launch {
                try {
                    acknowledgeWithRetry({ acknowledgeOnce(token) }, delaysMs, sleep)
                } finally {
                    inFlight.remove(token)
                }
            }
        }
}
