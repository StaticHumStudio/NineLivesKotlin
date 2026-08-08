package com.ninelivesaudio.app.entitlement

import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.service.SettingsManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings as the playback engine should see them, clamped by entitlement.
 *
 * The split matters. Anything that ACTS on a setting reads from here. Anything
 * that DISPLAYS the user's own choice keeps reading [SettingsManager] directly,
 * so a gated control shows what they picked, greyed, rather than silently
 * rewriting their preference to the free value.
 *
 * ## Consuming [effective] safely
 *
 * An entitlement change emits a whole new [AppSettings] here, with no marker
 * saying which field moved or why. A consumer that reacts to, say,
 * `autoRewindEnabled` changing by seeking would therefore move the playhead on
 * an entitlement transition, which is a hard invariant violation: no entitlement
 * transition may move playback position, in either direction.
 *
 * So consumers apply these values at the moments they already applied them
 * (load, play, an explicit user action) rather than reacting to the flow by
 * seeking. Today `PlaybackManager` reads [current] synchronously at exactly
 * those moments and nothing collects [effective] for playback control.
 */
@Singleton
class EffectiveSettingsRepository @Inject constructor(
    private val settingsManager: SettingsManager,
    private val entitlements: EntitlementRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val effective: StateFlow<AppSettings> =
        combine(settingsManager.settings, entitlements.state) { stored, entitlement ->
            EffectiveSettings.normalize(stored, entitlement)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = computeCurrent(),
        )

    /**
     * Synchronous read, for the call sites that are not coroutine-shaped.
     *
     * Deliberately NOT `effective.value`. `stateIn` collects on a launched
     * coroutine, so even with `Eagerly` there is a window where the cached value
     * is the initial one and both of its inputs were sampled separately. Reading
     * through it would let a just-downgraded install get one more play at 2.0x,
     * or one more auto-rewind, on the very transition this layer exists to
     * handle.
     *
     * Recomputing is a pure function over two current values. It costs a data
     * class copy and it cannot be stale.
     */
    val current: AppSettings get() = computeCurrent()

    private fun computeCurrent(): AppSettings =
        EffectiveSettings.normalize(settingsManager.currentSettings, entitlements.current)

    /**
     * Fresh synchronous read, for enforcement rather than display.
     *
     * Same reasoning as [current]: a StateFlow cached behind a launched
     * collector is the wrong thing to enforce a gate against.
     */
    val isUnlockedNow: Boolean get() = entitlements.current.isUnlocked

    /** Convenience for gates that only need the boolean, for UI observation. */
    val isUnlocked: StateFlow<Boolean> = entitlements.state
        .map { it.isUnlocked }
        .stateIn(scope, SharingStarted.Eagerly, entitlements.current.isUnlocked)
}
