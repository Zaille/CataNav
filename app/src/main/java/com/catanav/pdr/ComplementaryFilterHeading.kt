package com.catanav.pdr

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Manual gyro + magnetometer complementary filter — the fallback heading source for
 * environments where the fused rotation vector degrades (metal, rebar, magnetic rock).
 *
 * Gyroscope Z integration provides the short-term heading change (smooth, drifts);
 * the tilt-compensated compass (accelerometer + magnetometer, AOSP getRotationMatrix
 * math) provides the long-term absolute reference (absolute, noisy). Blend:
 *
 *   heading += gyroDelta;  heading += (1 - alpha) * shortestDelta(heading, magHeading)
 *
 * All angle blending is circular (via shortestDelta) — never a plain average, which
 * breaks at the 0/360 seam.
 */
class ComplementaryFilterHeading(
    private val alpha: Double = 0.98,
) : HeadingEstimator {

    override var headingDeg: Double = Double.NaN
        private set

    private var lastGyroTimestampNs: Long = 0
    private var magHeadingDeg: Double = Double.NaN
    private var accel: DoubleArray? = null
    private var magnetic: DoubleArray? = null

    @Volatile
    private var magAccuracyConfidence = HeadingConfidence.HIGH
    @Volatile
    private var fieldMagnitudeOk = true

    override val confidence: HeadingConfidence
        get() = if (!fieldMagnitudeOk) HeadingConfidence.UNRELIABLE else magAccuracyConfidence

    /** Gyroscope sample, rad/s, device coordinates. Timestamp in nanoseconds. */
    fun onGyro(timestampNs: Long, wz: Double) {
        if (headingDeg.isNaN()) {
            lastGyroTimestampNs = timestampNs
            return // wait for first absolute fix from the compass
        }
        if (lastGyroTimestampNs != 0L) {
            val dt = (timestampNs - lastGyroTimestampNs) / 1e9
            if (dt in 0.0..1.0) {
                // Device z-axis points out of the screen; a clockwise turn (viewed from
                // above, screen up) is a NEGATIVE wz, and heading grows clockwise.
                headingDeg = HeadingEstimator.normalize(headingDeg - Math.toDegrees(wz * dt))
            }
        }
        lastGyroTimestampNs = timestampNs
        blendMagnetic()
    }

    fun onAccelerometer(values: FloatArray) {
        accel = doubleArrayOf(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
        updateMagHeading()
    }

    fun onMagnetometer(values: FloatArray) {
        magnetic = doubleArrayOf(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
        val magnitude = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2].toDouble())
        // Earth's field is ~25-65 uT; far outside that means local magnetic interference.
        fieldMagnitudeOk = magnitude in 15.0..90.0
        updateMagHeading()
    }

    fun onMagnetometerAccuracyChanged(sensorStatusAccuracy: Int) {
        magAccuracyConfidence = HeadingEstimator.confidenceFromAccuracy(sensorStatusAccuracy)
    }

    /** Tilt-compensated compass azimuth — same math as SensorManager.getRotationMatrix. */
    private fun updateMagHeading() {
        val a = accel ?: return
        val e = magnetic ?: return
        // H = E x A (east), normalize; M = A x H (north-ish)
        val hx = e[1] * a[2] - e[2] * a[1]
        val hy = e[2] * a[0] - e[0] * a[2]
        val hz = e[0] * a[1] - e[1] * a[0]
        val normH = sqrt(hx * hx + hy * hy + hz * hz)
        if (normH < 0.1) return // device in free fall or field parallel to gravity
        val ihx = hx / normH; val ihy = hy / normH; val ihz = hz / normH
        val normA = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        if (normA < 0.1) return
        val ax = a[0] / normA; val ay = a[1] / normA; val az = a[2] / normA
        val mx = ay * ihz - az * ihy
        val my = az * ihx - ax * ihz
        // Rotation matrix rows: R = [H; M; A]; azimuth = atan2(R[1], R[4]) = atan2(ihy, my)
        val azimuthRad = atan2(ihy, my)
        magHeadingDeg = HeadingEstimator.normalize(Math.toDegrees(azimuthRad))
        if (headingDeg.isNaN()) {
            headingDeg = magHeadingDeg // first absolute fix seeds the filter
        }
    }

    private fun blendMagnetic() {
        if (headingDeg.isNaN() || magHeadingDeg.isNaN() || !isReliable) return
        val correction = (1 - alpha) * HeadingEstimator.shortestDelta(headingDeg, magHeadingDeg)
        headingDeg = HeadingEstimator.normalize(headingDeg + correction)
    }

    override fun reset() {
        headingDeg = Double.NaN
        magHeadingDeg = Double.NaN
        lastGyroTimestampNs = 0
        accel = null
        magnetic = null
        magAccuracyConfidence = HeadingConfidence.HIGH
        fieldMagnitudeOk = true
    }
}
