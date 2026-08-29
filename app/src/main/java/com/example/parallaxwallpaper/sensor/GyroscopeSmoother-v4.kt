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
import kotlin.math.PI

/**
 * V4 orientation tracker.
 *
 * Preferred path:
 *   GAME_ROTATION_VECTOR -> relative pitch/roll -> smooth persistent tilt.
 *
 * This is closer to DepthWeaver's DeviceOrientation behaviour than integrating
 * raw gyroscope angular velocity. When the user tilts the phone and holds it,
 * the wallpaper now stays at that viewpoint instead of decaying back to center.
 *
 * A raw-gyroscope fallback is retained for unusual devices without a rotation
 * vector sensor.
 */
class GyroscopeSmoother(
    context: Context,
    private val maxAngleDeg: Float = 14f,
    private val smoothingAlpha: Float = 0.16f
) : SensorEventListener {

    data class TiltOffset(
        val x: Float,
        val y: Float
    )

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val orientationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val gyroFallback: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var referencePitchDeg: Float? = null
    private var referenceRollDeg: Float? = null

    private var smoothPitchDeg = 0f
    private var smoothRollDeg = 0f

    // Fallback-only state.
    private var lastGyroTimestamp = 0L
    private var fallbackPitchDeg = 0f
    private var fallbackRollDeg = 0f

    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    private val _tiltFlow = MutableSharedFlow<TiltOffset>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tiltFlow: SharedFlow<TiltOffset> =
        _tiltFlow.asSharedFlow()

    var currentTilt = TiltOffset(0f, 0f)
        private set

    val isAvailable: Boolean
        get() = orientationSensor != null || gyroFallback != null

    fun start(
        samplingUs: Int = SensorManager.SENSOR_DELAY_GAME
    ) {
        resetReference()

        val selected = orientationSensor ?: gyroFallback
        selected?.let {
            sensorManager.registerListener(
                this,
                it,
                samplingUs
            )
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        lastGyroTimestamp = 0L
    }

    fun reset() {
        resetReference()
    }

    private fun resetReference() {
        referencePitchDeg = null
        referenceRollDeg = null

        smoothPitchDeg = 0f
        smoothRollDeg = 0f

        fallbackPitchDeg = 0f
        fallbackRollDeg = 0f
        lastGyroTimestamp = 0L

        currentTilt = TiltOffset(0f, 0f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                handleRotationVector(event)
            }

            Sensor.TYPE_GYROSCOPE -> {
                // Only used if no rotation-vector sensor exists.
                if (orientationSensor == null) {
                    handleGyroFallback(event)
                }
            }
        }
    }

    private fun handleRotationVector(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(
            rotationMatrix,
            event.values
        )
        SensorManager.getOrientation(
            rotationMatrix,
            orientationValues
        )

        val pitchDeg =
            Math.toDegrees(
                orientationValues[1].toDouble()
            ).toFloat()

        val rollDeg =
            Math.toDegrees(
                orientationValues[2].toDouble()
            ).toFloat()

        if (
            referencePitchDeg == null ||
            referenceRollDeg == null
        ) {
            referencePitchDeg = pitchDeg
            referenceRollDeg = rollDeg
            return
        }

        val deltaPitch = wrapDegrees(
            pitchDeg - referencePitchDeg!!
        ).coerceIn(-maxAngleDeg, maxAngleDeg)

        val deltaRoll = wrapDegrees(
            rollDeg - referenceRollDeg!!
        ).coerceIn(-maxAngleDeg, maxAngleDeg)

        smoothPitchDeg +=
            (deltaPitch - smoothPitchDeg) * smoothingAlpha

        smoothRollDeg +=
            (deltaRoll - smoothRollDeg) * smoothingAlpha

        publish(
            horizontalDeg = smoothRollDeg,
            verticalDeg = smoothPitchDeg
        )
    }

    private fun handleGyroFallback(event: SensorEvent) {
        val now = event.timestamp

        val dt =
            if (lastGyroTimestamp == 0L) {
                0f
            } else {
                (now - lastGyroTimestamp) * 1e-9f
            }

        lastGyroTimestamp = now

        if (dt <= 0f || dt > 0.1f) return

        val ratePitch =
            Math.toDegrees(
                event.values[0].toDouble()
            ).toFloat()

        val rateRoll =
            Math.toDegrees(
                event.values[1].toDouble()
            ).toFloat()

        fallbackPitchDeg =
            (fallbackPitchDeg + ratePitch * dt)
                .coerceIn(-maxAngleDeg, maxAngleDeg)

        fallbackRollDeg =
            (fallbackRollDeg + rateRoll * dt)
                .coerceIn(-maxAngleDeg, maxAngleDeg)

        smoothPitchDeg +=
            (fallbackPitchDeg - smoothPitchDeg) *
                smoothingAlpha

        smoothRollDeg +=
            (fallbackRollDeg - smoothRollDeg) *
                smoothingAlpha

        // Very gentle fallback drift correction. Unlike V3's 0.97 per sensor
        // event, this will not visibly snap the wallpaper back to center.
        fallbackPitchDeg *= 0.9995f
        fallbackRollDeg *= 0.9995f

        publish(
            horizontalDeg = smoothRollDeg,
            verticalDeg = smoothPitchDeg
        )
    }

    private fun publish(
        horizontalDeg: Float,
        verticalDeg: Float
    ) {
        val tilt = TiltOffset(
            x = (horizontalDeg / maxAngleDeg)
                .coerceIn(-1f, 1f),
            y = (verticalDeg / maxAngleDeg)
                .coerceIn(-1f, 1f)
        )

        currentTilt = tilt
        _tiltFlow.tryEmit(tilt)
    }

    private fun wrapDegrees(value: Float): Float {
        var v = value
        while (v > 180f) v -= 360f
        while (v < -180f) v += 360f
        return v
    }

    override fun onAccuracyChanged(
        sensor: Sensor,
        accuracy: Int
    ) = Unit
}
