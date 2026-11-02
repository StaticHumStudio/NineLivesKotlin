package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A load that never makes its book current closes the server session it opened (#105). */
class LoadServerSessionTest {

    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = playbackScope.cancel()

    /**
     * The player's single server session slot, the sessions the server
     * opened and the closes sent. [endLoad] mirrors the load's finally block
     * and [answer] mirrors openServerListeningSession taking or closing a
     * session: whoever takes the session out of the slot is the one that
     * closes it.
     */
    private class Player {
        private val lock = Any()
        var currentSessionId: String? = null
        val serverOpened = mutableListOf<String>()
        val closed = mutableListOf<String>()

        fun endLoad(load: LoadServerSession) = synchronized(lock) {
            val toClose = load.sessionToClose(currentSessionId)
            if (toClose != null) {
                currentSessionId = null
                closed += toClose
            }
        }

        fun answer(load: LoadServerSession, sessionId: String) = synchronized(lock) {
            if (load.opened(sessionId)) currentSessionId = sessionId else closed += sessionId
        }

        /** What a new load does with a session left in the slot before it opens its own. */
        fun closeOrphan() = synchronized(lock) {
            currentSessionId?.let { closed += it }
            currentSessionId = null
        }
    }

    /** A server whose open request is under way until [reply] is completed. */
    private class SlowServer(private val player: Player, private val id: String) {
        val requestStarted = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val answered = CompletableDeferred<Unit>()

        suspend fun open(load: LoadServerSession) {
            // The server opens the session when the request lands, whether
            // or not the app is still around for the answer.
            synchronized(player) { player.serverOpened += id }
            requestStarted.complete(Unit)
            reply.await()
            player.answer(load, id)
            answered.complete(Unit)
        }
    }

    @Test
    fun `leaving the page while the session open is in flight still closes that session`() = runBlocking {
        val player = Player()
        val load = LoadServerSession()
        val server = SlowServer(player, "s1")

        val job = launch(Dispatchers.Default) {
            try {
                awaitLoadSessionOpen(playbackScope, load, timeout = null) { server.open(load) }
                awaitCancellation()
            } finally {
                player.endLoad(load)
            }
        }
        server.requestStarted.await()
        job.cancel()
        job.join()

        // The answer lands after the page is gone.
        server.reply.complete(Unit)
        withTimeout(5.seconds) { server.answered.await() }

        assertEquals(listOf("s1"), player.serverOpened)
        assertEquals(listOf("s1"), player.closed)
        assertNull(player.currentSessionId)
    }

    @Test
    fun `an open that outlives the downloaded book's wait is closed when it answers`() = runBlocking {
        val player = Player()
        val load = LoadServerSession()
        val server = SlowServer(player, "s1")

        // The load gives up on the open and goes on to play from disk.
        awaitLoadSessionOpen(playbackScope, load, timeout = 50.milliseconds) { server.open(load) }
        load.published()

        server.reply.complete(Unit)
        withTimeout(5.seconds) { server.answered.await() }
        player.endLoad(load)

        assertEquals(listOf("s1"), player.closed)
        assertNull(player.currentSessionId)
    }

    @Test
    fun `an open that answers in time belongs to the book the load publishes`() = runBlocking {
        val player = Player()
        val load = LoadServerSession()
        val server = SlowServer(player, "s1")
        server.reply.complete(Unit)

        awaitLoadSessionOpen(playbackScope, load, timeout = 5.seconds) { server.open(load) }
        load.published()
        player.endLoad(load)

        assertEquals(emptyList<String>(), player.closed)
        assertEquals("s1", player.currentSessionId)
    }

    @Test
    fun `leaving the page after the session opened closes that session`() = runBlocking {
        val player = Player()
        val load = LoadServerSession()
        val opened = CompletableDeferred<Unit>()

        val job = launch {
            try {
                player.answer(load, "s1")
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
    fun `a session answered after the load ended is refused`() {
        val load = LoadServerSession()
        assertNull(load.sessionToClose(null))
        assertFalse(load.opened("late"))
    }

    @Test
    fun `a published load keeps its session for the book it made current`() {
        val player = Player()
        val load = LoadServerSession()
        player.answer(load, "s1")
        load.published()
        player.endLoad(load)
        assertEquals(emptyList<String>(), player.closed)
        assertEquals("s1", player.currentSessionId)
    }

    @Test
    fun `leaving mid load then opening another book closes only the abandoned session`() {
        val player = Player()
        val first = LoadServerSession()
        player.answer(first, "s1")
        // Leaving the page ends the first load.
        player.endLoad(first)

        // The next book's load finds nothing left over, then opens its own.
        val second = LoadServerSession()
        player.closeOrphan()
        player.answer(second, "s2")
        second.published()
        player.endLoad(second)

        assertEquals(listOf("s1"), player.closed)
        assertEquals("s2", player.currentSessionId)
    }

    @Test
    fun `an abandoned load never closes the session a newer load opened`() {
        val player = Player()
        val first = LoadServerSession()
        player.answer(first, "s1")

        // A newer load took the first one's session as an orphan and opened
        // its own before the first load reached its finally.
        player.closeOrphan()
        player.answer(LoadServerSession(), "s2")

        player.endLoad(first)
        assertEquals(listOf("s1"), player.closed)
        assertEquals("s2", player.currentSessionId)
    }
}
