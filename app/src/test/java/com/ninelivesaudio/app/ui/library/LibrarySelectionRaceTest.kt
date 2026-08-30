package com.ninelivesaudio.app.ui.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * onLibrarySelected used to launch a bare viewModelScope.launch { ... } on
 * every tap with no cancellation of the previous one. Selecting a slow,
 * eventually-failing library A and then a fast, successful library B let A's
 * coroutine keep running: it finished after B and persisted FAILED, so B's
 * shelf showed a false failure banner. ExclusiveLaunch fixes this by
 * cancelling A's job outright the instant B is selected, so A never reaches
 * its completion side effects.
 */
class LibrarySelectionRaceTest {

    @Test
    fun `selecting a new library cancels the still-running previous selection`() = runBlocking {
        val recorded = mutableListOf<String>()
        val launcher = ExclusiveLaunch()
        val scope = CoroutineScope(Job())
        val slowLibraryStarted = CompletableDeferred<Unit>()

        val slowLibrary = launcher.launch(scope) {
            slowLibraryStarted.complete(Unit)
            delay(30_000) // "library A": slow, would eventually persist a failure
            recorded += "A: FAILED"
        }
        slowLibraryStarted.await()

        val fastLibrary = launcher.launch(scope) {
            recorded += "B: SUCCESS" // "library B": fast, succeeds
        }
        fastLibrary.join()
        // Wait for A's cancellation to actually unwind before inspecting
        // `recorded`. Otherwise a still-running, not-yet-cancelled A would
        // make this assertion pass by dumb luck (it just hasn't gotten to
        // its delay(30_000) payoff yet), which would prove nothing.
        slowLibrary.join()

        assertTrue(slowLibrary.isCancelled)
        assertEquals(listOf("B: SUCCESS"), recorded)
        scope.cancel()
    }

    @Test
    fun `a selection cancelled by a newer one reports no failure of its own`() = runBlocking {
        // Mirrors loadAudioBooks's own catch block: cancellation must escape
        // uncaught instead of turning into "Failed to load audiobooks".
        var errorMessage: String? = null
        val launcher = ExclusiveLaunch()
        val scope = CoroutineScope(Job())
        val started = CompletableDeferred<Unit>()

        val cancelledSelection = launcher.launch(scope) {
            try {
                started.complete(Unit)
                delay(30_000)
                errorMessage = "unreachable: should have been cancelled first"
            } catch (e: Exception) {
                rethrowLibraryLoadCancellation(e)
                errorMessage = "Failed to load audiobooks: ${e.message}"
            }
        }
        started.await()
        launcher.launch(scope) {}.join()
        cancelledSelection.join()

        assertTrue(cancelledSelection.isCancelled)
        assertNull(errorMessage)
        scope.cancel()
    }

    @Test
    fun `an unsuperseded selection runs to completion like a plain launch`() = runBlocking {
        val recorded = mutableListOf<String>()
        val launcher = ExclusiveLaunch()
        val scope = CoroutineScope(Job())

        launcher.launch(scope) { recorded += "only selection" }.join()

        assertEquals(listOf("only selection"), recorded)
        scope.cancel()
    }

    @Test
    fun `selection cancels an initial or refresh load running in the same lane`() = runBlocking {
        val recorded = mutableListOf<String>()
        val launcher = ExclusiveLaunch()
        val scope = CoroutineScope(Job())
        val initialLoadStarted = CompletableDeferred<Unit>()

        val initialLoad = launcher.launch(scope) {
            initialLoadStarted.complete(Unit)
            delay(30_000)
            recorded += "initial load"
        }
        initialLoadStarted.await()

        launcher.launch(scope) { recorded += "selection" }.join()
        initialLoad.join()

        assertTrue(initialLoad.isCancelled)
        assertEquals(listOf("selection"), recorded)
        scope.cancel()
    }

    @Test
    fun `cancelled refresh does not clear the newer refresh indicator`() = runBlocking {
        val launcher = ExclusiveLaunch()
        val scope = CoroutineScope(Job())
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        var isRefreshing = false

        val first = launcher.launch(scope) {
            isRefreshing = true
            firstStarted.complete(Unit)
            try {
                delay(30_000)
            } finally {
                updateLibraryLoadStateIfActive { isRefreshing = false }
            }
        }
        firstStarted.await()

        val second = launcher.launch(scope) {
            isRefreshing = true
            secondStarted.complete(Unit)
            releaseSecond.await()
            isRefreshing = false
        }
        secondStarted.await()
        first.join()

        assertTrue(isRefreshing)
        releaseSecond.complete(Unit)
        second.join()
        assertFalse(isRefreshing)
        scope.cancel()
    }
}
