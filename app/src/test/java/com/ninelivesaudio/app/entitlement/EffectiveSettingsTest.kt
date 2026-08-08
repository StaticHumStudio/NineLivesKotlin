package com.ninelivesaudio.app.entitlement

import com.ninelivesaudio.app.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Normalization decides what every paying user does and does not get, and what
 * a downgraded user keeps. Both directions are pinned here.
 */
class EffectiveSettingsTest {

    /** Everything premium turned on, so a downgrade has something to clamp. */
    private val premium = AppSettings(
        playbackSpeed = 2.0,
        autoRewindEnabled = true,
        autoRewindMode = "flat",
        autoRewindSeconds = 30,
        eqEnabled = true,
        eqBandGains = listOf(3, 2, 1, 2, 3),
        volumeBoostGain = 800,
        skipSilenceEnabled = true,
        sleepTimerMotionEnabled = true,
        sleepTimerShakeResetEnabled = true,
        sleepTimerRewindSeconds = 15,
    )

    private val unlocked = EntitlementState(true, EntitlementSource.PLAY_UNLOCK)
    private val legacy = EntitlementState(true, EntitlementSource.LEGACY_PAID)

    // ─── Unlocked passes through untouched ────────────────────────────────────

    @Test
    fun `an unlocked install gets exactly what it stored`() {
        assertSame(premium, EffectiveSettings.normalize(premium, unlocked))
    }

    @Test
    fun `a grandfathered install gets exactly what it stored`() {
        assertSame(premium, EffectiveSettings.normalize(premium, legacy))
    }

    // ─── Free clamps every gated value ────────────────────────────────────────

    @Test
    fun `free is pinned to normal speed`() {
        val effective = EffectiveSettings.normalize(premium, EntitlementState.FREE)

        assertEquals(EffectiveSettings.FREE_SPEED, effective.playbackSpeed, 0.0)
    }

    /**
     * autoRewindEnabled defaults to TRUE in AppSettings, so this is a real
     * downgrade rather than a no-op. Free resumes exactly where playback stopped.
     */
    @Test
    fun `free loses auto-rewind even though it defaults on`() {
        assertTrue(AppSettings().autoRewindEnabled)

        assertFalse(EffectiveSettings.normalize(premium, EntitlementState.FREE).autoRewindEnabled)
    }

    @Test
    fun `free loses the equalizer and the volume boost`() {
        val effective = EffectiveSettings.normalize(premium, EntitlementState.FREE)

        assertFalse(effective.eqEnabled)
        assertEquals(0, effective.volumeBoostGain)
    }

    @Test
    fun `free loses silence skipping`() {
        assertFalse(EffectiveSettings.normalize(premium, EntitlementState.FREE).skipSilenceEnabled)
    }

    /** All three sleep-timer extras default premium-on, so all three must clamp. */
    @Test
    fun `free loses every sleep timer extra`() {
        val effective = EffectiveSettings.normalize(premium, EntitlementState.FREE)

        assertFalse(effective.sleepTimerMotionEnabled)
        assertFalse(effective.sleepTimerShakeResetEnabled)
        assertEquals(0, effective.sleepTimerRewindSeconds)
    }

    // ─── Stored values are never wiped ────────────────────────────────────────

    /**
     * A downgrade is not a factory reset. Normalization returns a copy, so
     * rebuying restores the user's setup exactly instead of dropping them back
     * to defaults with everything they configured gone.
     */
    @Test
    fun `normalizing does not mutate the stored settings`() {
        EffectiveSettings.normalize(premium, EntitlementState.FREE)

        assertEquals(2.0, premium.playbackSpeed, 0.0)
        assertTrue(premium.eqEnabled)
        assertTrue(premium.skipSilenceEnabled)
        assertEquals(800, premium.volumeBoostGain)
    }

    /**
     * The values a free user is allowed to keep configuring must survive intact,
     * or clamping would quietly reset unrelated preferences.
     */
    @Test
    fun `free keeps everything that is not gated`() {
        val stored = premium.copy(
            serverUrl = "https://books.example.com",
            autoSyncProgress = false,
            syncIntervalMinutes = 15,
            eqBandGains = listOf(3, 2, 1, 2, 3),
            autoRewindSeconds = 30,
            autoRewindMode = "flat",
        )

        val effective = EffectiveSettings.normalize(stored, EntitlementState.FREE)

        assertEquals("https://books.example.com", effective.serverUrl)
        assertFalse(effective.autoSyncProgress)
        assertEquals(15, effective.syncIntervalMinutes)
        // Retained on purpose: the band gains and rewind shape are meaningless
        // while the features are off, and they are exactly what should come back
        // untouched on unlock.
        assertEquals(listOf(3, 2, 1, 2, 3), effective.eqBandGains)
        assertEquals(30, effective.autoRewindSeconds)
        assertEquals("flat", effective.autoRewindMode)
    }

    // ─── Round trip ───────────────────────────────────────────────────────────

    /**
     * Downgrade then rebuy has to land back where it started. If normalization
     * ever wrote through to storage, this is the test that would catch it.
     */
    @Test
    fun `downgrade then unlock restores the original setup`() {
        val downgraded = EffectiveSettings.normalize(premium, EntitlementState.FREE)
        assertNotEquals(premium, downgraded)

        val restored = EffectiveSettings.normalize(premium, unlocked)
        assertEquals(premium, restored)
    }

    @Test
    fun `normalizing is idempotent for a free install`() {
        val once = EffectiveSettings.normalize(premium, EntitlementState.FREE)
        val twice = EffectiveSettings.normalize(once, EntitlementState.FREE)

        assertEquals(once, twice)
    }
}
