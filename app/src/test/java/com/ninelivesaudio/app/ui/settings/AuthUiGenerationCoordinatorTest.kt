package com.ninelivesaudio.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex round 3: round 2 made Refresh, Test Connection, and manual Sync
 * advance the SAME authUiGeneration counter Connect/Disconnect use to guard
 * a confirmed mutation. That closed the round-2 banner race but opened a
 * worse one: Refresh holds the auth mutex, the user confirms Disconnect
 * (queued behind it), and taps Test or Sync while waiting -- Sync/Test don't
 * need the mutex, run immediately, and their tap advanced the SAME counter
 * Disconnect's currency check depends on. The confirmed logout silently
 * no-op'd and the token stayed active.
 *
 * AuthUiGenerationCoordinator's two counters fix this at the type level:
 * these tests pin the four invariants directly.
 */
class AuthUiGenerationCoordinatorTest {

    @Test
    fun `an observational tap never invalidates a queued mutation`() {
        val coordinator = AuthUiGenerationCoordinator()

        val disconnectGeneration = coordinator.beginMutation()
        // Test or Sync tapped while Disconnect is still queued on the mutex.
        coordinator.beginObservational()

        assertTrue(coordinator.isMutationCurrent(disconnectGeneration))
    }

    @Test
    fun `a second observational tap also never invalidates a queued mutation`() {
        val coordinator = AuthUiGenerationCoordinator()

        val disconnectGeneration = coordinator.beginMutation()
        coordinator.beginObservational() // Refresh
        coordinator.beginObservational() // then Sync, then Test -- any number

        assertTrue(coordinator.isMutationCurrent(disconnectGeneration))
    }

    @Test
    fun `a newer mutation invalidates an older mutation`() {
        val coordinator = AuthUiGenerationCoordinator()

        val connectGeneration = coordinator.beginMutation()
        coordinator.beginMutation() // e.g. the user immediately disconnects

        assertFalse(coordinator.isMutationCurrent(connectGeneration))
    }

    @Test
    fun `a mutation invalidates an in-flight observational banner`() {
        val coordinator = AuthUiGenerationCoordinator()

        val refreshGeneration = coordinator.beginObservational()
        coordinator.beginMutation() // Disconnect fires while Refresh is running

        assertFalse(coordinator.isObservationalCurrent(refreshGeneration))
    }

    @Test
    fun `a newer observational tap invalidates an earlier observational tap`() {
        val coordinator = AuthUiGenerationCoordinator()

        val refreshGeneration = coordinator.beginObservational()
        coordinator.beginObservational() // Sync tapped after Refresh

        assertFalse(coordinator.isObservationalCurrent(refreshGeneration))
    }

    @Test
    fun `an observational tap with nothing else happening stays current`() {
        val coordinator = AuthUiGenerationCoordinator()

        val generation = coordinator.beginObservational()

        assertTrue(coordinator.isObservationalCurrent(generation))
    }

    @Test
    fun `currentAuthGeneration reflects only mutations, matching isMutationCurrent`() {
        val coordinator = AuthUiGenerationCoordinator()

        val captured = coordinator.currentAuthGeneration()
        coordinator.beginObservational() // must not move it
        assertTrue(coordinator.isMutationCurrent(captured))

        coordinator.beginMutation() // must move it
        assertFalse(coordinator.isMutationCurrent(captured))
    }
}
