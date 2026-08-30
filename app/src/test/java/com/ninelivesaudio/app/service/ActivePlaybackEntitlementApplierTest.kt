package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.entitlement.EffectiveSettings
import com.ninelivesaudio.app.entitlement.EntitlementResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.TimeUnit

class ActivePlaybackEntitlementApplierTest {

    @Test
    fun `trial expiry clamps live rendering without moving playback position`() {
        val start = TimeUnit.DAYS.toMillis(20_000)
        val expired = EntitlementResolver.resolve(
            legacyPaid = false,
            playUnlocked = false,
            trialStartedAtEpochMs = start,
            trialConsumed = true,
            nowEpochMs = start + TimeUnit.DAYS.toMillis(14),
        )
        assertFalse(expired.isUnlocked)
        val effective = EffectiveSettings.normalize(
            AppSettings(
                playbackSpeed = 2.0,
                eqEnabled = true,
                volumeBoostGain = 800,
                skipSilenceEnabled = true,
            ),
            expired,
        )
        val target = RecordingPlaybackTarget(
            speed = 2.0f,
            equalizerEnabled = true,
            volumeBoostGain = 800,
            silenceSkippingEnabled = true,
            positionMs = 73_452L,
        )

        ActivePlaybackEntitlementApplier.apply(effective, target)

        assertEquals(1.0f, target.speed, 0.0f)
        assertFalse(target.equalizerEnabled)
        assertEquals(0, target.volumeBoostGain)
        assertFalse(target.silenceSkippingEnabled)
        assertEquals(73_452L, target.positionMs)
    }
}

private class RecordingPlaybackTarget(
    override var speed: Float,
    override var equalizerEnabled: Boolean,
    override var volumeBoostGain: Int,
    override var silenceSkippingEnabled: Boolean,
    val positionMs: Long,
) : ActivePlaybackRenderTarget
