package com.catanav.pdr

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Heading from Android's fused TYPE_ROTATION_VECTOR, reimplemented as pure math
 * (quaternion → rotation matrix → azimuth, same formulas as
 * SensorManager.getRotationMatrixFromVector + getOrientation) so it is testable
 * off-device with synthetic quaternions.
 */
class RotationVectorHeading : HeadingEstimator {

    override var headingDeg: Double = Double.NaN
        private set

    @Volatile
    override var confidence: HeadingConfidence = HeadingConfidence.HIGH
        private set

    /** [values] is the raw TYPE_ROTATION_VECTOR event array: x, y, z[, w[, accuracy]]. */
    fun onRotationVector(values: FloatArray) {
        val q1 = values[0].toDouble() // x
        val q2 = values[1].toDouble() // y
        val q3 = values[2].toDouble() // z
        val q0 = if (values.size >= 4 && !values[3].isNaN()) {
            values[3].toDouble()
        } else {
            val t = 1.0 - q1 * q1 - q2 * q2 - q3 * q3
            if (t > 0) sqrt(t) else 0.0
        }

        val sqQ1 = 2.0 * q1 * q1
        val sqQ3 = 2.0 * q3 * q3
        val q1q2 = 2.0 * q1 * q2
        val q3q0 = 2.0 * q3 * q0

        // Row-major 3x3 rotation matrix entries needed for azimuth:
        val r1 = q1q2 - q3q0        // R[1]
        val r4 = 1.0 - sqQ1 - sqQ3  // R[4]
        val azimuthRad = atan2(r1, r4)
        headingDeg = HeadingEstimator.normalize(Math.toDegrees(azimuthRad))
    }

    /** Fed from onAccuracyChanged / the event's accuracy field. */
    fun onAccuracyChanged(sensorStatusAccuracy: Int) {
        confidence = HeadingEstimator.confidenceFromAccuracy(sensorStatusAccuracy)
    }

    override fun reset() {
        headingDeg = Double.NaN
        confidence = HeadingConfidence.HIGH
    }
}
