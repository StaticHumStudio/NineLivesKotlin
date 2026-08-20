package com.ninelivesaudio.app.ui.dossier

import com.ninelivesaudio.app.entitlement.FreeTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live-downgrade leg of the Dossier clamp.
 *
 * The clamp inside loadDossier covers a downgrade observed across a fresh load.
 * It does NOT cover a revocation that lands while the ViewModel is retained and
 * the screen is on top, because nothing re-reads entitlement in that window.
 * This is the policy that closes it.
 */
class DossierEntitlementReloadTest {

    @Test
    fun `revocation under a paid window forces a reload`() {
        val paidPeriods = DossierPeriod.entries.filterNot { it in FreeTier.DOSSIER_PERIODS }
        assertTrue("no paid periods left to test", paidPeriods.isNotEmpty())
        paidPeriods.forEach { period ->
            assertTrue(
                "$period must reload when the unlock goes away",
                dossierNeedsReloadOnEntitlementChange(period, isUnlocked = false),
            )
        }
    }

    @Test
    fun `revocation under a free window leaves the report alone`() {
        FreeTier.DOSSIER_PERIODS.forEach { period ->
            assertFalse(
                "$period is free and must not churn a reload",
                dossierNeedsReloadOnEntitlementChange(period, isUnlocked = false),
            )
        }
    }

    @Test
    fun `an unlocked reader never reloads on entitlement noise`() {
        DossierPeriod.entries.forEach { period ->
            assertFalse(
                "$period must not reload while unlocked",
                dossierNeedsReloadOnEntitlementChange(period, isUnlocked = true),
            )
        }
    }
}
