package com.ninelivesaudio.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadManagerOwnerScopeTest {

    @Test
    fun `stale scope blocks a selected remote row before mutation`() {
        assertFalse(downloadMutationGate(scopeCurrent = false, rowMatches = true))
    }

    @Test
    fun `scope switch at the write gate blocks the old action`() = runBlocking {
        val writeGate = CompletableDeferred<Unit>()
        val actionReachedGate = CompletableDeferred<Unit>()
        var scopeCurrent = true

        val result = async {
            actionReachedGate.complete(Unit)
            writeGate.await()
            downloadMutationGate(scopeCurrent = scopeCurrent, rowMatches = true)
        }
        actionReachedGate.await()
        scopeCurrent = false
        writeGate.complete(Unit)

        assertFalse(result.await())
    }

    @Test
    fun `changed row identity blocks mutation even when scope remains current`() {
        assertFalse(downloadMutationGate(scopeCurrent = true, rowMatches = false))
    }

    @Test
    fun `current scope and unchanged row permit mutation`() {
        assertTrue(downloadMutationGate(scopeCurrent = true, rowMatches = true))
    }
}
