package com.ninelivesaudio.app.service.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadEngineScopeFenceTest {

    @Test
    fun `filesystem mutation does not run when scope changes at its gate`() = runBlocking {
        val reachedGate = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        var current = true
        var touched = false

        val result = async {
            runScopedFilesystemMutation(
                isCurrent = {
                    reachedGate.complete(Unit)
                    releaseGate.await()
                    current
                },
                mutation = { touched = true },
            )
        }
        reachedGate.await()
        current = false
        releaseGate.complete(Unit)

        assertFalse(result.await())
        assertFalse(touched)
    }

    @Test
    fun `filesystem mutation runs only after a current scope check`() = runBlocking {
        var touched = false
        assertTrue(runScopedFilesystemMutation(isCurrent = { true }) { touched = true })
        assertTrue(touched)
    }
}
