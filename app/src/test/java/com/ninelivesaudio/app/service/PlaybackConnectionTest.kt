package com.ninelivesaudio.app.service

import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.*
import org.junit.Test

class PlaybackConnectionTest {
    private class Controller(var connected: Boolean = true)

    @Test
    fun `completed connection is adopted before its main callback runs`() {
        val future = SettableFuture.create<Controller>()
        val connected = Controller()
        val callbacks = mutableListOf<Runnable>()
        var installed: Controller? = null
        future.addListener({ installed = future.get() }, { callbacks += it })
        future.set(connected)
        assertNull(installed)
        assertTrue(retainPlaybackConnection(installed, future, { it.connected }) { installed = it })
        assertSame(connected, installed)
        callbacks.single().run()
        assertSame(connected, installed)
    }

    @Test
    fun `unfinished connection is reused without waiting or installing a controller`() {
        val pending = SettableFuture.create<Controller>()
        repeat(2) {
            assertTrue(retainPlaybackConnection(null, pending, { it.connected }) { fail("Not complete") })
        }
        assertFalse(pending.isCancelled)
    }

    @Test
    fun `failed canceled and disconnected connections permit another attempt`() {
        val failed = SettableFuture.create<Controller>().apply { setException(IllegalStateException("Service unavailable")) }
        val canceled = SettableFuture.create<Controller>().apply { cancel(true) }
        val disconnected = SettableFuture.create<Controller>().apply { set(Controller(false)) }
        for (future in listOf(failed, canceled, disconnected)) {
            assertFalse(retainPlaybackConnection(null, future, { it.connected }) { fail("Not connected") })
        }
    }

    @Test
    fun `installed live controller survives repeated starts`() {
        val installed = Controller()
        assertTrue(retainPlaybackConnection(installed, null, { it.connected }) { fail("Already installed") })
    }
}
