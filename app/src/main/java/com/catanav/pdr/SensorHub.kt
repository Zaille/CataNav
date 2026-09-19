package com.catanav.pdr

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * Binds Android sensors to the pure PDR components and reports the capability level.
 * Availability is checked at STARTUP (never discovered mid-trip): the user must know
 * whether they have Full PDR, degraded heading, or manual mode BEFORE relying on the
 * app underground.
 */
class SensorHub(
    private val sensorManager: SensorManager,
    preferredSource: HeadingSource,
) : SensorEventListener {

    enum class HeadingSource { ROTATION_VECTOR, COMPLEMENTARY_FILTER }

    enum class Capability {
        /** Step detection + heading available. */
        FULL_PDR,

        /** No usable step input — arrow buttons + heading dial drive movement. */
        MANUAL_MODE,

        /** No heading source at all — PDR cannot run, tell the user plainly. */
        NO_HEADING,
    }

    data class Health(
        val capability: Capability,
        val headingSourceUsed: HeadingSource?,
        val headingReliable: Boolean,
        val headingConfidence: HeadingConfidence,
        val stepSensorPresent: Boolean,
    )

    private val linearAccel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationHeading = RotationVectorHeading()
    private val complementaryHeading = ComplementaryFilterHeading()

    private val complementaryAvailable = gyroscope != null && magnetometer != null && accelerometer != null

    /** Actual source in use after availability fallback. */
    val activeSource: HeadingSource? = when {
        preferredSource == HeadingSource.ROTATION_VECTOR && rotationVector != null -> HeadingSource.ROTATION_VECTOR
        preferredSource == HeadingSource.COMPLEMENTARY_FILTER && complementaryAvailable -> HeadingSource.COMPLEMENTARY_FILTER
        rotationVector != null -> HeadingSource.ROTATION_VECTOR
        complementaryAvailable -> HeadingSource.COMPLEMENTARY_FILTER
        else -> null
    }

    val headingEstimator: HeadingEstimator? = when (activeSource) {
        HeadingSource.ROTATION_VECTOR -> rotationHeading
        HeadingSource.COMPLEMENTARY_FILTER -> complementaryHeading
        null -> null
    }

    val stepDetector = StepDetector()

    /**
     * Step detection runs on TYPE_LINEAR_ACCELERATION, which needs no runtime
     * permission (ACTIVITY_RECOGNITION only guards the hardware step counter, which
     * is not used). A user who declines that permission still gets full PDR.
     */
    val capability: Capability
        get() = when {
            headingEstimator == null -> Capability.NO_HEADING
            linearAccel == null -> Capability.MANUAL_MODE
            else -> Capability.FULL_PDR
        }

    private val _health = MutableStateFlow(
        Health(
            Capability.NO_HEADING, activeSource,
            headingReliable = true, headingConfidence = HeadingConfidence.HIGH,
            stepSensorPresent = linearAccel != null,
        ),
    )
    val health: StateFlow<Health> = _health

    /** Fired once per detected step with the event timestamp (ms since boot). */
    var onStep: ((timestampMs: Long) -> Unit)? = null

    /** Fired on heading changes (throttled to meaningful movement). */
    var onHeading: ((headingDeg: Double) -> Unit)? = null

    private var lastReportedHeading = Double.NaN
    private var running = false

    fun start() {
        if (running) return
        running = true
        _health.value = Health(
            capability = capability,
            headingSourceUsed = activeSource,
            headingReliable = headingEstimator?.isReliable ?: false,
            headingConfidence = headingEstimator?.confidence ?: HeadingConfidence.UNRELIABLE,
            stepSensorPresent = linearAccel != null,
        )
        val delay = SensorManager.SENSOR_DELAY_GAME
        when (activeSource) {
            HeadingSource.ROTATION_VECTOR ->
                rotationVector?.let { sensorManager.registerListener(this, it, delay) }
            HeadingSource.COMPLEMENTARY_FILTER -> {
                gyroscope?.let { sensorManager.registerListener(this, it, delay) }
                magnetometer?.let { sensorManager.registerListener(this, it, delay) }
                accelerometer?.let { sensorManager.registerListener(this, it, delay) }
            }
            null -> Unit
        }
        if (_health.value.capability == Capability.FULL_PDR) {
            linearAccel?.let { sensorManager.registerListener(this, it, delay) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val m = sqrt(
                    (event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]).toDouble(),
                )
                if (stepDetector.onSample(event.timestamp / 1_000_000, m)) {
                    onStep?.invoke(event.timestamp / 1_000_000)
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                rotationHeading.onRotationVector(event.values)
                reportHeading(rotationHeading.headingDeg)
            }
            Sensor.TYPE_GYROSCOPE -> {
                complementaryHeading.onGyro(event.timestamp, event.values[2].toDouble())
                reportHeading(complementaryHeading.headingDeg)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                complementaryHeading.onMagnetometer(event.values)
                refreshReliability()
            }
            Sensor.TYPE_ACCELEROMETER -> complementaryHeading.onAccelerometer(event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // SENSOR_STATUS_UNRELIABLE / ACCURACY_LOW => surface the figure-8 prompt
        // instead of silently integrating a degraded heading.
        when (sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> rotationHeading.onAccuracyChanged(accuracy)
            Sensor.TYPE_MAGNETIC_FIELD -> complementaryHeading.onMagnetometerAccuracyChanged(accuracy)
        }
        refreshReliability()
    }

    private fun refreshReliability() {
        val reliable = headingEstimator?.isReliable ?: false
        val conf = headingEstimator?.confidence ?: HeadingConfidence.UNRELIABLE
        val h = _health.value
        if (h.headingReliable != reliable || h.headingConfidence != conf) {
            _health.value = h.copy(headingReliable = reliable, headingConfidence = conf)
        }
    }

    private fun reportHeading(deg: Double) {
        if (deg.isNaN()) return
        if (lastReportedHeading.isNaN() ||
            kotlin.math.abs(HeadingEstimator.shortestDelta(lastReportedHeading, deg)) > 1.0
        ) {
            lastReportedHeading = deg
            onHeading?.invoke(deg)
        }
        refreshReliability()
    }
}
