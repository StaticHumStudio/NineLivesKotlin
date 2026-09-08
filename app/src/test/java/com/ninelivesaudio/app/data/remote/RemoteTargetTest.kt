package com.ninelivesaudio.app.data.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteTargetTest {
    private val record = StoredAuthRecord("fixture-token", "https://abs.example/a", "account-a")

    @Test fun `accepted route and account produce a stable owner across token rotation`() {
        val first = captureRemoteTarget(record, "https://abs.example/a/", 4, true)!!
        val rotated = captureRemoteTarget(record.copy(token = "rotated-fixture"), record.serverUrl!!, 5, true)!!
        assertEquals(first.owner.key, rotated.owner.key)
        assertNotEquals(first, rotated)
        assertEquals(64, first.owner.key.length)
        assertFalse(first.owner.key.contains(record.token))
    }

    @Test fun `accounts and reverse proxy paths do not share owners`() {
        val a = captureRemoteTarget(record, record.serverUrl!!, 1, true)!!
        val b = captureRemoteTarget(record.copy(serverUrl = "https://abs.example/b"), "https://abs.example/b", 1, true)!!
        val otherAccount = captureRemoteTarget(record.copy(accountId = "account-b"), record.serverUrl!!, 1, true)!!
        assertNotEquals(a.owner.key, b.owner.key)
        assertNotEquals(a.owner.key, otherAccount.owner.key)
    }

    @Test fun `route normalization preserves path identity and rejects credentials and query`() {
        assertEquals(ServerRoute.parse("https://abs.example/a"), ServerRoute.parse("HTTPS://ABS.EXAMPLE:443/a///"))
        assertNotEquals(ServerRoute.parse("https://abs.example/a"), ServerRoute.parse("https://abs.example/ab"))
        assertNotEquals(ServerRoute.parse("https://abs.example/a%2Fb"), ServerRoute.parse("https://abs.example/a/b"))
        for (url in listOf("", "ftp://abs.example", "https://user:pass@abs.example/a", "https://abs.example/a?q=1", "https://abs.example/a#x")) {
            assertNull(url, ServerRoute.parse(url))
        }
    }

    @Test fun `unknown owner mismatched route and inactive runtime cannot activate a target`() {
        assertNull(captureRemoteTarget(null, record.serverUrl!!, 1, true))
        assertNull(captureRemoteTarget(record.copy(accountId = null), record.serverUrl!!, 1, true))
        assertNull(captureRemoteTarget(record.copy(accountId = " "), record.serverUrl!!, 1, true))
        assertNull(captureRemoteTarget(record.copy(token = ""), record.serverUrl!!, 1, true))
        assertNull(captureRemoteTarget(record, "https://abs.example/b", 1, true))
        assertNull(captureRemoteTarget(record, record.serverUrl!!, 1, false))
    }

    @Test fun `auth record diagnostics never disclose token route or account`() {
        val text = record.toString()
        assertFalse(text.contains(record.token))
        assertFalse(text.contains(record.serverUrl!!))
        assertFalse(text.contains(record.accountId!!))
    }
}
