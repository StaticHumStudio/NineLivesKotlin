package com.ninelivesaudio.app.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolledProgressSaveTest {

    @Test
    fun `the first sample of a play is saved at once`() {
        assertTrue(polledReportForcesSave(saveRequest = 0L, lastDeliveredSaveRequest = null))
        assertTrue(polledReportForcesSave(saveRequest = 7L, lastDeliveredSaveRequest = null))
    }

    @Test
    fun `a sample with no new save request rides the cadence`() {
        assertFalse(polledReportForcesSave(saveRequest = 3L, lastDeliveredSaveRequest = 3L))
    }

    @Test
    fun `a sample taken after a seek or chapter change is saved at once`() {
        assertTrue(polledReportForcesSave(saveRequest = 4L, lastDeliveredSaveRequest = 3L))
    }

    @Test
    fun `a dropped sample does not lose the save request it carried`() = runBlocking {
        // The poll channel keeps only the newest unsent sample. A seek bumps the
        // request, its sample is replaced before the worker reads it, and the
        // replacing sample must still force the save.
        val channel = newPolledProgressReportChannel()
        channel.send(PolledProgressReport("book", currentTime = 10.0, duration = 100.0, saveRequest = 0L))
        val first = channel.receive()
        var lastDelivered: Long? = null
        assertTrue(polledReportForcesSave(first.saveRequest, lastDelivered))
        lastDelivered = first.saveRequest

        // Seek: request 1. Its sample is overwritten by the next tick's.
        channel.trySend(PolledProgressReport("book", currentTime = 60.0, duration = 100.0, saveRequest = 1L))
        channel.trySend(PolledProgressReport("book", currentTime = 60.5, duration = 100.0, saveRequest = 1L))
        val delivered = channel.receive()

        assertEquals(60.5, delivered.currentTime, 0.0)
        assertTrue(polledReportForcesSave(delivered.saveRequest, lastDelivered))
        lastDelivered = delivered.saveRequest
        assertFalse(polledReportForcesSave(1L, lastDelivered))
    }
}
