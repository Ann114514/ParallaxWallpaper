package com.example.parallaxwallpaper.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs

/**
 * Listens to the gyroscope and produces smoothed tilt offsets in [-1, 1].
 *
 * Algorithm:
 *   1. Integrate raw angular-velocity (rad/s) × Δt → accumulated angle.
 *   2. Apply exponential low-pass filter to remove high-frequency jitter.
 *   3. Apply centering damping each frame so the wallpaper drifts back to
 *      center when the phone is held still (prevents unbounded accumulation).
 *   4. Clamp to ±maxAngleDeg and normalize to [-1, 1].
 */
class GyroscopeSmoother(
    context: Context,
    private val maxAngleDeg: Float = 12f,
    private val smoothingAlpha: Float = 0.18f,   // lower = more smoothing
    private val centeringDecay: Float = 0.97f,   // per-frame return-to-zero
) : SensorEventListener {

    data class TiltOffset(val x: Float, val y: Float)

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var lastTimestamp = 0L

    // Raw integrated angles (degrees)
    private var rawX = 0f
    private var rawY = 0f

    // Smoothed angles (degrees)
    private var smoothX = 0f
    private var smoothY = 0f

    private val _tiltFlow = MutableSharedFlow<TiltOffset>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tiltFlow: SharedFlow<TiltOffset> = _tiltFlow.asSharedFlow()

    /** Current snapshot without collecting the flow. */
    var currentTilt = TiltOffset(0f, 0f)
        private set

    val isAvailable: Boolean get() = gyro != null

    fun start(samplingUs: Int = SensorManager.SENSOR_DELAY_GAME) {
        gyro?.let { sensorManager.registerListener(this, it, samplingUs) }
        lastTimestamp = 0L
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        lastTimestamp = 0L
    }

    fun reset() {
        rawX = 0f; rawY = 0f; smoothX = 0f; smoothY = 0f
        lastTimestamp = 0L
        currentTilt = TiltOffset(0f, 0f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        val now = event.timestamp
        val dt = if (lastTimestamp == 0L) 0f else (now - lastTimestamp) * 1e-9f
        lastTimestamp = now

        if (dt <= 0f || dt > 0.1f) return  // skip stale / huge gaps

        // Gyro axes: X = pitch (tilt forward/back), Y = roll (tilt left/right)
        // event.values[0] = rate around X (pitch), [1] = rate around Y (roll)
        val gyroX = Math.toDegrees(event.values[0].toDouble()).toFloat()
        val gyroY = Math.toDegrees(event.values[1].toDouble()).toFloat()

        // Integrate
        rawX = (rawX + gyroX * dt).coerceIn(-maxAngleDeg, maxAngleDeg)
        rawY = (rawY + gyroY * dt).coerceIn(-maxAngleDeg, maxAngleDeg)

        // Apply centering decay (drift back to neutral)
        rawX *= centeringDecay
        rawY *= centeringDecay

        // Low-pass smooth
        smoothX = smoothingAlpha * rawX + (1f - smoothingAlpha) * smoothX
        smoothY = smoothingAlpha * rawY + (1f - smoothingAlpha) * smoothY

        // Normalize to [-1, 1]
        val tilt = TiltOffset(
            x = smoothX / maxAngleDeg,
            y = smoothY / maxAngleDeg
        )
        currentTilt = tilt
        _tiltFlow.tryEmit(tilt)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
