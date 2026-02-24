package com.ninelivesaudio.app.service

import android.util.Log
import dagger.Lazy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val TAG = "SleepTimerManager"
private val GRACE_WINDOW = 60.seconds
private val MOTION_EXTENSION = 2.minutes

/**
 * Manages sleep timer countdown, motion-based grace windows, and shake-to-reset.
 *
 * Flow:
 * ```
 * Timer running → countdown hits 0
 *     ├─ motionSensingEnabled == false → pause immediately
 *     └─ motionSensingEnabled == true → enter grace window (60s)
 *         ├─ motion detected → extend +2 minutes, exit grace
 *         └─ no motion for 60s → pause + rewind
 * ```
 */
@Singleton
class SleepTimerManager @Inject constructor(
    private val settingsManager: SettingsManager,
    private val playbackManagerLazy: Lazy<PlaybackManager>,
) {
    data class SleepTimerState(
        val isActive: Boolean = false,
        val remaining: Duration = Duration.ZERO,
        val originalDuration: Duration = Duration.ZERO,
        val isInGracePeriod: Boolean = false,
        val motionDetected: Boolean = false,
    )

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null
    private var graceJob: Job? = null

    fun start(minutes: Int) {
        cancel()
        val duration = minutes.minutes
        Log.d(TAG, "start: ${minutes}m")
        _state.value = SleepTimerState(
            isActive = true,
            remaining = duration,
            originalDuration = duration,
        )
        timerJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val current = _state.value.remaining - 1.seconds
                if (current <= Duration.ZERO) {
                    onCountdownReachedZero()
                    break
                }
                _state.update { it.copy(remaining = current) }
            }
        }
    }

    fun cancel() {
        Log.d(TAG, "cancel")
        timerJob?.cancel()
        timerJob = null
        graceJob?.cancel()
        graceJob = null
        _state.value = SleepTimerState()
    }

    /** Called by ShakeDetector when the phone is shaken. */
    fun onShakeDetected() {
        val settings = settingsManager.currentSettings
        val current = _state.value
        if (!current.isActive || !settings.sleepTimerShakeResetEnabled) return

        Log.d(TAG, "onShakeDetected: resetting to ${current.originalDuration}")
        // If in grace period, cancel it and go back to normal countdown
        graceJob?.cancel()
        graceJob = null

        _state.update {
            it.copy(
                remaining = it.originalDuration,
                isInGracePeriod = false,
                motionDetected = false,
            )
        }

        // Restart the countdown loop if it was stopped (grace period case)
        if (timerJob?.isActive != true) {
            timerJob = scope.launch {
                while (isActive) {
                    delay(1_000)
                    val remaining = _state.value.remaining - 1.seconds
                    if (remaining <= Duration.ZERO) {
                        onCountdownReachedZero()
                        break
                    }
                    _state.update { it.copy(remaining = remaining) }
                }
            }
        }
    }

    /** Called by ShakeDetector with motion status updates. */
    fun onMotionUpdate(isMoving: Boolean) {
        val current = _state.value
        if (!current.isActive) return

        _state.update { it.copy(motionDetected = isMoving) }

        // Motion during grace period → extend timer and exit grace
        if (current.isInGracePeriod && isMoving) {
            Log.d(TAG, "onMotionUpdate: motion during grace → extending by $MOTION_EXTENSION")
            graceJob?.cancel()
            graceJob = null
            _state.update {
                it.copy(
                    remaining = MOTION_EXTENSION,
                    isInGracePeriod = false,
                    motionDetected = false,
                )
            }
            // Restart countdown
            timerJob = scope.launch {
                while (isActive) {
                    delay(1_000)
                    val remaining = _state.value.remaining - 1.seconds
                    if (remaining <= Duration.ZERO) {
                        onCountdownReachedZero()
                        break
                    }
                    _state.update { it.copy(remaining = remaining) }
                }
            }
        }
    }

    private fun onCountdownReachedZero() {
        val settings = settingsManager.currentSettings
        if (settings.sleepTimerMotionEnabled && !_state.value.isInGracePeriod) {
            Log.d(TAG, "onCountdownReachedZero: entering grace window")
            _state.update {
                it.copy(
                    remaining = Duration.ZERO,
                    isInGracePeriod = true,
                )
            }
            // Start grace window countdown
            graceJob = scope.launch {
                delay(GRACE_WINDOW.inWholeMilliseconds)
                // Grace expired with no motion → stop
                Log.d(TAG, "Grace window expired, no motion → stopping")
                onTimerExpired()
            }
        } else {
            onTimerExpired()
        }
    }

    private fun onTimerExpired() {
        Log.d(TAG, "onTimerExpired: pausing playback")
        val pm = playbackManagerLazy.get()
        val rewindSec = settingsManager.currentSettings.sleepTimerRewindSeconds
        if (rewindSec > 0) {
            val current = pm.position.value
            val target = (current - rewindSec.seconds).coerceAtLeast(Duration.ZERO)
            pm.seekTo(target)
        }
        pm.pause()
        // Reset state
        timerJob?.cancel()
        timerJob = null
        graceJob?.cancel()
        graceJob = null
        _state.value = SleepTimerState()
    }
}
