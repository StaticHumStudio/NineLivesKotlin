package com.ninelivesaudio.app.data.remote

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Issue #118: listening saved offline goes to Audiobookshelf dated by the
 * phone, and the server orders it against other devices by that date. A
 * phone clock hours fast made old listening look newest. The server's Date
 * header tells how far the phone's clock is off, so the dates can be sent in
 * the server's time.
 */
class ServerClockTest {

    private val hour = 3_600_000L
    // A whole second, as a Date header carries.
    private val serverNow = 1_794_858_990_000L

    private fun httpDate(ms: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(java.util.Date(ms))

    private fun response(date: String?, sentAt: Long, receivedAt: Long): Response =
        Response.Builder()
            .request(Request.Builder().url("http://abs.example/ping").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .apply { if (date != null) header("Date", date) }
            .sentRequestAtMillis(sentAt)
            .receivedResponseAtMillis(receivedAt)
            .build()

    @Test
    fun `a phone clock four hours fast is measured as four hours ahead of the server`() {
        val clock = ServerClock()
        val phoneNow = serverNow + 4 * hour
        clock.observe(response(httpDate(serverNow), phoneNow - 40, phoneNow + 40), elapsedNowMs = 1_000)

        val offset = clock.offsetMs(elapsedNowMs = 2_000)!!
        // Within the Date header's one second.
        assertEquals(-4 * hour.toDouble(), offset.toDouble(), 1_000.0)
    }

    @Test
    fun `a phone clock four hours slow is measured as four hours behind`() {
        val clock = ServerClock()
        val phoneNow = serverNow - 4 * hour
        clock.observe(response(httpDate(serverNow), phoneNow, phoneNow + 20), elapsedNowMs = 1_000)

        assertEquals(4 * hour.toDouble(), clock.offsetMs(elapsedNowMs = 1_500)!!.toDouble(), 1_000.0)
    }

    @Test
    fun `no Date header measures nothing`() {
        val clock = ServerClock()
        clock.observe(response(null, serverNow, serverNow + 10), elapsedNowMs = 1_000)
        clock.observe(response("not a date", serverNow, serverNow + 10), elapsedNowMs = 1_000)
        assertNull(clock.offsetMs(elapsedNowMs = 1_000))
    }

    @Test
    fun `an old reading is not used for a delivery now`() {
        val clock = ServerClock()
        clock.observe(response(httpDate(serverNow), serverNow + hour, serverNow + hour), elapsedNowMs = 1_000)

        assertEquals(-hour.toDouble(), clock.offsetMs(elapsedNowMs = 1_000 + SERVER_CLOCK_FRESH_MS)!!.toDouble(), 1_000.0)
        assertNull(clock.offsetMs(elapsedNowMs = 1_001 + SERVER_CLOCK_FRESH_MS))
        // Elapsed time never runs back within one boot, so a reading "from
        // the future" belongs to an earlier boot.
        assertNull(clock.offsetMs(elapsedNowMs = 999))
    }

    @Test
    fun `the latest reading wins`() {
        val clock = ServerClock()
        clock.observe(response(httpDate(serverNow), serverNow + hour, serverNow + hour), elapsedNowMs = 1_000)
        clock.observe(response(httpDate(serverNow), serverNow, serverNow), elapsedNowMs = 2_000)
        assertEquals(0.0, clock.offsetMs(elapsedNowMs = 2_000)!!.toDouble(), 1_000.0)
    }

    @Test
    fun `small offsets and no reading send the phone's times as they are`() {
        assertEquals(0L, serverTimeShiftMs(null))
        assertEquals(0L, serverTimeShiftMs(1_999))
        assertEquals(0L, serverTimeShiftMs(-1_999))
        assertEquals(2_000L, serverTimeShiftMs(2_000))
        assertEquals(-4 * hour, serverTimeShiftMs(-4 * hour))
    }
}
