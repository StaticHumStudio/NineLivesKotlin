package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A load that never makes its book current closes the server session it opened (#105). */
class LoadServerSessionTest {

    /**
     * The player's single server session slot, and the closes sent. The
     * take-and-clear mirrors the load's finally block: whoever takes the
     * session out of the slot is the one that closes it.
     */
    private class Player {
        var currentSessionId: String? = null
        val closed = mutableListOf<String>()

        fun endLoad(load: LoadServerSession) {
            val toClose = load.sessionToClose(currentSessionId)
            if (toClose != null) {
                currentSessionId = null
                closed += toClose
            }
        }

        /** What a new load does with a session left in the slot before it opens its own. */
        fun closeOrphan() {
            currentSessionId?.let { closed += it }
            currentSessionId = null
        }
    }

    @Test
    fun `leaving the page after the session opened closes that session`() = runBlocking {
        val player = Player()
        val load = LoadServerSession()
        val opened = CompletableDeferred<Unit>()

        val job = launch {
            try {
                player.currentSessionId = "s1"
                load.opened("s1")
                opened.complete(Unit)
                awaitCancellation()
            } finally {
                player.endLoad(load)
            }
        }
        opened.await()
        job.cancel()
        job.join()

        assertEquals(listOf("s1"), player.closed)
        assertNull(player.currentSessionId)
    }

    @Test
    fun `leaving the page before the session opened closes nothing`() {
        val player = Player()
        val load = LoadServerSession()
        player.endLoad(load)
        assertEquals(emptyList<String>(), player.closed)
    }

    @Test
    fun `a published load keeps its session for the book it made current`() {
        val player = Player()
        val load = LoadServerSession()
        player.currentSessionId = "s1"
        load.opened("s1")
        load.published()
        player.endLoad(load)
        assertEquals(emptyList<String>(), player.closed)
        assertEquals("s1", player.currentSessionId)
    }

    @Test
    fun `leaving mid load then opening another book closes only the abandoned session`() {
        val player = Player()
        val first = LoadServerSession()
        player.currentSessionId = "s1"
        first.opened("s1")
        // Leaving the page ends the first load.
        player.endLoad(first)

        // The next book's load finds nothing left over, then opens its own.
        val second = LoadServerSession()
        player.closeOrphan()
        player.currentSessionId = "s2"
        second.opened("s2")
        second.published()
        player.endLoad(second)

        assertEquals(listOf("s1"), player.closed)
        assertEquals("s2", player.currentSessionId)
    }

    @Test
    fun `an abandoned load never closes the session a newer load opened`() {
        val player = Player()
        val first = LoadServerSession()
        player.currentSessionId = "s1"
        first.opened("s1")

        // A newer load took the first one's session as an orphan and opened
        // its own before the first load reached its finally.
        player.closeOrphan()
        player.currentSessionId = "s2"

        player.endLoad(first)
        assertEquals(listOf("s1"), player.closed)
        assertEquals("s2", player.currentSessionId)
    }
}
