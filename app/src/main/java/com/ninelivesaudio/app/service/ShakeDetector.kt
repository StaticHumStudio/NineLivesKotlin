package com.ninelivesaudio.app.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

private const val TAG = "ShakeDetector"

/**
 * Accelerometer wrapper providing two signals:
 *
 * 1. **Shake detection** — strong motion (~2.5g) sustained >100ms → [onShake] callback.
 * 2. **Motion detection** — whether the device has moved significantly (>0.3g delta from
 *    resting) within the last 5 seconds → [onMotionUpdate] callback.
 *
 * Only registered when the sleep timer is active — no battery drain otherwise.
 * No manifest permissions required for `TYPE_ACCELEROMETER`.
 */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit,
    private val onMotionUpdate: (Boolean) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Shake detection thresholds
    private val shakeThresholdG = 2.5f
    private val shakeMinDurationMs = 100L
    private val shakeDebounceMs = 500L

    // Motion detection thresholds
    private val motionDeltaThreshold = 0.3f  // g-force delta from resting
    private val motionWindowMs = 5_000L

    // Shake state
    private var shakeStartTime = 0L
    private var lastShakeCallbackTime = 0L

    // Motion state
    private var lastMotionTime = 0L
    private var restingX = 0f
    private var restingY = 0f
    private var restingZ = 0f
    private var hasResting = false
    private var isRegistered = false

    fun register() {
        if (isRegistered) return
        if (accelerometer == null) {
            Log.w(TAG, "No accelerometer available on this device")
            return
        }
        sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI,
        )
        isRegistered = true
        // Preserve resting calibration across register/unregister cycles
        // so motion detection doesn't mis-calibrate if the user is holding the phone.
        Log.d(TAG, "Registered accelerometer listener (hasResting=$hasResting)")
    }

    fun unregister() {
        if (!isRegistered) return
        sensorManager.unregisterListener(this)
        isRegistered = false
        // Keep hasResting/restingX/Y/Z intact for next register() cycle
        Log.d(TAG, "Unregistered accelerometer listener")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val now = System.currentTimeMillis()

        // ── Shake Detection ─────────────────────────────────────────
        // Calculate magnitude in g-force units (subtract gravity ≈ 9.81)
        val magnitude = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        if (magnitude > shakeThresholdG) {
            if (shakeStartTime == 0L) {
                shakeStartTime = now
            } else if (now - shakeStartTime >= shakeMinDurationMs) {
                // Sustained shake above threshold
                if (now - lastShakeCallbackTime > shakeDebounceMs) {
                    lastShakeCallbackTime = now
                    Log.d(TAG, "Shake detected (magnitude=${magnitude}g)")
                    onShake()
                }
                shakeStartTime = 0L
            }
        } else {
            shakeStartTime = 0L
        }

        // ── Motion Detection ────────────────────────────────────────
        if (!hasResting) {
            // Calibrate resting position from first reading
            restingX = x
            restingY = y
            restingZ = z
            hasResting = true
            return
        }

        val dx = x - restingX
        val dy = y - restingY
        val dz = z - restingZ
        val delta = sqrt(dx * dx + dy * dy + dz * dz) / SensorManager.GRAVITY_EARTH

        if (delta > motionDeltaThreshold) {
            val wasMoving = now - lastMotionTime < motionWindowMs
            lastMotionTime = now
            if (!wasMoving) {
                onMotionUpdate(true)
            }
            // Slowly adapt resting position (low-pass filter)
            restingX = restingX * 0.95f + x * 0.05f
            restingY = restingY * 0.95f + y * 0.05f
            restingZ = restingZ * 0.95f + z * 0.05f
        } else {
            if (lastMotionTime > 0 && now - lastMotionTime >= motionWindowMs) {
                onMotionUpdate(false)
                lastMotionTime = 0L
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
