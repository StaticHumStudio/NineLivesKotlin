package com.ninelivesaudio.app.data.remote

import android.os.SystemClock
import com.ninelivesaudio.app.service.SettingsManager
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * How far the phone's clock is from the Audiobookshelf server's, read from
 * the Date header of the server's responses (#118). Listening saved offline
 * goes to the server dated by the phone, and the server orders it against
 * other devices by that date, so a phone clock hours fast made old listening
 * look newest. The delivery shifts the dates into the server's time.
 */
@Singleton
class ServerClock(
    private val currentServerUrl: () -> String,
) {
    @Inject constructor(settingsManager: SettingsManager) : this({ settingsManager.currentSettings.serverUrl })

    private data class Reading(val offsetMs: Long, val atElapsedMs: Long)

    // Each server's own reading, so a server switch never uses another
    // server's offset.
    private val readings = ConcurrentHashMap<String, Reading>()

    /** Record the offset a response shows. No readable Date header records nothing. */
    fun observe(response: Response, elapsedNowMs: Long) {
        val serverDateMs = response.headers.getDate("Date")?.time ?: return
        readings[serverClockKey(response.request.url)] = Reading(
            offsetMs = clockOffsetMs(
                serverDateMs = serverDateMs,
                sentAtMs = response.sentRequestAtMillis,
                receivedAtMs = response.receivedResponseAtMillis,
            ),
            atElapsedMs = elapsedNowMs,
        )
    }

    /**
     * Server time minus phone time for the server the app uses now, from
     * its response within the last [SERVER_CLOCK_FRESH_MS], or null with
     * none that recent.
     */
    fun offsetMs(elapsedNowMs: Long): Long? {
        val server = validatedServerBaseUrl(currentServerUrl()) ?: return null
        val last = readings[serverClockKey(server)] ?: return null
        val age = elapsedNowMs - last.atElapsedMs
        return if (age in 0..SERVER_CLOCK_FRESH_MS) last.offsetMs else null
    }
}

/** Feeds every server response's Date header to [ServerClock]. */
class ServerClockInterceptor @Inject constructor(
    private val serverClock: ServerClock,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        serverClock.observe(response, SystemClock.elapsedRealtime())
        return response
    }
}

/** One server: scheme, host and port. */
private fun serverClockKey(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"

/** How recent a reading has to be for a delivery to use it. */
internal const val SERVER_CLOCK_FRESH_MS = 5 * 60_000L

/** Below this the clocks agree well enough, and the phone's times go as they are. */
internal const val SERVER_CLOCK_MIN_SHIFT_MS = 2_000L

/**
 * Server time minus phone time. The Date header counts whole seconds, so
 * the server's time is taken as the middle of that second, against the
 * middle of the request on the phone.
 */
internal fun clockOffsetMs(serverDateMs: Long, sentAtMs: Long, receivedAtMs: Long): Long =
    serverDateMs + 500 - (sentAtMs + receivedAtMs) / 2

/** What to add to a phone time to send it as server time: nothing for a small or unknown offset. */
internal fun serverTimeShiftMs(offsetMs: Long?): Long =
    if (offsetMs == null || abs(offsetMs) < SERVER_CLOCK_MIN_SHIFT_MS) 0L else offsetMs
