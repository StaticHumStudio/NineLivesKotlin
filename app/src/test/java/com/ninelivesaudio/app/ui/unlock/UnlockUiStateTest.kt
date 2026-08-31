package com.ninelivesaudio.app.ui.unlock

import com.ninelivesaudio.app.entitlement.EntitlementSource
import com.ninelivesaudio.app.entitlement.EntitlementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockUiStateTest {

    @Test
    fun `a free unused install can start the trial and still purchase`() {
        val state = UnlockUiState(
            entitlement = EntitlementState.FREE.copy(trialOfferAvailable = true),
        )

        assertTrue(state.canStartTrial)
        assertTrue(state.canPurchase)
        assertFalse(state.isTrialActive)
    }

    @Test
    fun `an active trial keeps purchase available but cannot start again`() {
        val state = UnlockUiState(
            entitlement = EntitlementState(
                isUnlocked = true,
                source = EntitlementSource.TRIAL,
                trialDaysRemaining = 14,
            ),
        )

        assertTrue(state.isTrialActive)
        assertTrue(state.canPurchase)
        assertFalse(state.canStartTrial)
        assertEquals("14 days remain in your trial.", state.trialRemainingText)
    }

    @Test
    fun `a consumed expired trial keeps purchase but never offers another trial`() {
        val state = UnlockUiState(entitlement = EntitlementState.FREE)

        assertFalse(state.isTrialActive)
        assertFalse(state.canStartTrial)
        assertTrue(state.canPurchase)
    }

    @Test
    fun `a Play purchase removes both purchase and trial actions`() {
        val state = UnlockUiState(
            entitlement = EntitlementState(true, EntitlementSource.PLAY_UNLOCK),
        )

        assertFalse(state.canPurchase)
        assertFalse(state.canStartTrial)
    }

    @Test
    fun `a legacy grant removes both purchase and trial actions`() {
        val state = UnlockUiState(
            entitlement = EntitlementState(true, EntitlementSource.LEGACY_PAID),
        )

        assertFalse(state.canPurchase)
        assertFalse(state.canStartTrial)
    }

    @Test
    fun `one remaining day uses singular copy`() {
        val state = UnlockUiState(
            entitlement = EntitlementState(
                isUnlocked = true,
                source = EntitlementSource.TRIAL,
                trialDaysRemaining = 1,
            ),
        )

        assertEquals("1 day remains in your trial.", state.trialRemainingText)
    }
}
