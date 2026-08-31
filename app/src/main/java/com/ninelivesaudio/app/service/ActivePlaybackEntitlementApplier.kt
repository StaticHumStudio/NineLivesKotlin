package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppSettings

/**
 * The rendering capabilities an entitlement transition may change.
 *
 * There is deliberately no seek or position setter here. Keeping that power
 * out of the interface makes the no-position-movement contract structural.
 */
internal interface ActivePlaybackRenderTarget {
    var speed: Float
    var equalizerEnabled: Boolean
    var volumeBoostGain: Int
    var silenceSkippingEnabled: Boolean
}

internal object ActivePlaybackEntitlementApplier {
    fun apply(settings: AppSettings, target: ActivePlaybackRenderTarget) {
        target.speed = settings.playbackSpeed.toFloat()
        target.equalizerEnabled = settings.eqEnabled
        target.volumeBoostGain = settings.volumeBoostGain
        target.silenceSkippingEnabled = settings.skipSilenceEnabled
    }
}
