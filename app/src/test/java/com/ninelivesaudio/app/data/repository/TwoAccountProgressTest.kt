package com.ninelivesaudio.app.data.repository

import com.ninelivesaudio.app.data.remote.ServerProgressRead
import com.ninelivesaudio.app.domain.model.UserProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration

/**
 * #118 final review: account B signs in on a phone where account A
 * listened. The phone's saved spots are per phone, not per account, so A's
 * spot for a book the server held under A must never be delivered into B's
 * account, and must never be reset because B has no progress for it.
 */
class TwoAccountProgressTest {

    @Test
    fun `a book another user's server held is left alone in the absent pass`() {
        assertEquals(
            AbsentOnServer.LEAVE,
            absentOnServerAction(serverHeldIt = false, heldByAnotherUser = true),
        )
        // Even if both held it, B having no progress now is not A's reset.
        assertEquals(
            AbsentOnServer.RESET,
            absentOnServerAction(serverHeldIt = true, heldByAnotherUser = true),
        )
        assertEquals(AbsentOnServer.DELIVER_FIRST, absentOnServerAction(serverHeldIt = false))
    }

    @Test
    fun `a saved spot queues for the signed-in user only when nobody else's server held it`() {
        assertTrue(unconfirmedSpotMayQueue(owner = "b", heldByOwner = false, heldByAnotherUser = false, hasUnsynced = false))
        // A's book: never into B's account.
        assertFalse(unconfirmedSpotMayQueue(owner = "b", heldByOwner = false, heldByAnotherUser = true, hasUnsynced = false))
        // No known user: nobody to queue it for.
        assertFalse(unconfirmedSpotMayQueue(owner = "", heldByOwner = false, heldByAnotherUser = false, hasUnsynced = false))
        assertFalse(unconfirmedSpotMayQueue(owner = "b", heldByOwner = true, heldByAnotherUser = false, hasUnsynced = false))
        assertFalse(unconfirmedSpotMayQueue(owner = "b", heldByOwner = false, heldByAnotherUser = false, hasUnsynced = true))
    }

    @Test
    fun `loading A's book as B keeps the phone's spot when B has none`() {
        // Missing for B is not a reset of A's spot: the load keeps the saved spot.
        assertEquals(
            ServerProgressRead.Unavailable,
            serverReadForLoad(ServerProgressRead.Missing, heldByAnotherUser = true),
        )
        assertEquals(ServerProgressRead.Missing, serverReadForLoad(ServerProgressRead.Missing, heldByAnotherUser = false))
        // B's own progress for the book is B's truth.
        val found = ServerProgressRead.Found(UserProgress(libraryItemId = "book", currentTime = Duration.ZERO, progress = 0.4, isFinished = false, lastUpdate = 1L))
        assertEquals(found, serverReadForLoad(found, heldByAnotherUser = true))
    }

    @Test
    fun `the two-account flow sends nothing of A's into B's account`() {
        // A listened and the server held the book under A. B signs in.
        val heldBy = mapOf("book" to setOf("a"))
        fun heldByOther(owner: String) = heldBy["book"].orEmpty().any { it != owner }
        fun heldByOwner(owner: String) = "book" in heldBy && owner in heldBy.getValue("book")
        val owner = "b"

        // Sync's absent pass: left alone, nothing queued, nothing reset.
        val action = absentOnServerAction(serverHeldIt = heldByOwner(owner), heldByAnotherUser = heldByOther(owner))
        assertEquals(AbsentOnServer.LEAVE, action)
        // The load and Play path: nothing queued for B, and Missing keeps A's spot.
        assertFalse(unconfirmedSpotMayQueue(owner, heldByOwner(owner), heldByOther(owner), hasUnsynced = false))
        assertEquals(ServerProgressRead.Unavailable, serverReadForLoad(ServerProgressRead.Missing, heldByOther(owner)))
    }

    // ─── Rows written while the user was not known ───────────────────────

    @Test
    fun `a write that read no user before a claim and landed after it is claimed next time`() = runBlocking {
        // #118 final review: the write read owner "" before the claim, its
        // insert landed after the claim's UPDATE, and the flag said nothing
        // was left, so the row stayed unowned until a restart.
        val rows = UnownedRows()
        val claimed = mutableListOf<String>()
        val unowned = mutableListOf<String>()
        suspend fun claim(user: String) { claimed += user; unowned.clear() }

        // The writer reads no user.
        val writer = rows.ownerForWrite("")
        // The claim runs for B (rows from 2.1.x).
        rows.claimFor("b") { claim(it) }
        // The writer's insert lands after the claim.
        unowned += "row"
        rows.wrote(writer)
        assertEquals(listOf("row"), unowned)

        // The next owner lookup claims it.
        rows.claimFor("b") { claim(it) }
        assertEquals(emptyList<String>(), unowned)
        assertEquals(listOf("b", "b"), claimed)
        // Nothing unowned left, so no more claims.
        rows.claimFor("b") { claim(it) }
        assertEquals(2, claimed.size)
    }

    @Test
    fun `a claim that fails is tried again`() = runBlocking {
        val rows = UnownedRows()
        runCatching { rows.claimFor("b") { error("disk") } }
        var ran = false
        rows.claimFor("b") { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `no user claims nothing`() = runBlocking {
        val rows = UnownedRows()
        var ran = false
        rows.claimFor("") { ran = true }
        assertFalse(ran)
    }
}
