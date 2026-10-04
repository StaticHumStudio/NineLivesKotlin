package com.ninelivesaudio.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 5-minute timer used to re-download every library's book list on every
 * tick. A big library on a slow home server paid tens of megabytes each
 * time. The timer now pulls progress every tick and the full list at most
 * hourly.
 */
class PeriodicItemSyncTest {

    private val server = "https://abs.example.net"
    private val minute = 60_000L

    @Test
    fun `no full sync yet means the tick runs one`() {
        assertTrue(shouldRefreshItemList(lastFullSync = null, serverUrl = server, nowElapsedMs = 0L))
    }

    @Test
    fun `a full sync inside the hour leaves the tick to progress only`() {
        val stamp = FullItemSyncStamp(server, atElapsedMs = 1_000L)
        assertFalse(shouldRefreshItemList(stamp, server, nowElapsedMs = 1_000L + 5 * minute))
        assertFalse(shouldRefreshItemList(stamp, server, nowElapsedMs = 1_000L + 59 * minute))
        assertFalse(shouldRefreshItemList(stamp, server, nowElapsedMs = 1_000L + 60 * minute - 1))
    }

    @Test
    fun `an hour after the last full sync the tick runs another`() {
        val stamp = FullItemSyncStamp(server, atElapsedMs = 1_000L)
        assertTrue(shouldRefreshItemList(stamp, server, nowElapsedMs = 1_000L + 60 * minute))
        assertTrue(shouldRefreshItemList(stamp, server, nowElapsedMs = 1_000L + 65 * minute))
    }

    @Test
    fun `a full sync of another server does not count for this one`() {
        val stamp = FullItemSyncStamp("https://old.example.net", atElapsedMs = 1_000L)
        assertTrue(shouldRefreshItemList(stamp, server, nowElapsedMs = 1_000L + 5 * minute))
    }

    @Test
    fun `a clock that went backward counts as due`() {
        val stamp = FullItemSyncStamp(server, atElapsedMs = 10 * minute)
        assertTrue(shouldRefreshItemList(stamp, server, nowElapsedMs = 5 * minute))
    }
}
