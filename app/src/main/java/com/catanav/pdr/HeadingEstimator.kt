package com.catanav.pdr

/**
 * How much the current heading can be trusted. Surfaced in the navigation UI
 * ("Heading: 137° — High confidence" / "Magnetic interference detected") and used to
 * grow the position uncertainty faster while degraded — PDR keeps running either way.
 */
enum class HeadingConfidence {
    HIGH, MEDIUM, LOW, UNRELIABLE;

    /** Rough 1-sigma heading uncertainty for [com.catanav.domain.PositionEstimate]. */
    val headingUncertaintyDegrees: Double
        get() = when (this) {
            HIGH -> 5.0
            MEDIUM -> 15.0
            LOW -> 30.0
            UNRELIABLE -> 90.0
        }
}

/**
 * A source of absolute compass heading (degrees, 0 = north, clockwise positive,
 * i.e. 90 = east). Two swappable implementations exist and are toggleable at runtime
 * in Settings for field comparison:
 *  - [RotationVectorHeading] (default) — Android's fused TYPE_ROTATION_VECTOR.
 *  - [ComplementaryFilterHeading] (fallback) — manual gyro+magnetometer blend; the
 *    fused rotation vector can degrade indoors near metal/rebar.
 *
 * Implementations are pure math; SensorHub feeds them raw sensor values.
 */
interface HeadingEstimator {
    /** Current heading in degrees [0, 360), or NaN while not yet initialized. */
    val headingDeg: Double

    /** Trust level combining sensor accuracy flags and magnetic-field sanity. */
    val confidence: HeadingConfidence

    /** Convenience: HIGH/MEDIUM are usable, LOW/UNRELIABLE trigger the warning UI. */
    val isReliable: Boolean
        get() = confidence == HeadingConfidence.HIGH || confidence == HeadingConfidence.MEDIUM

    fun reset()

    companion object {
        fun normalize(deg: Double): Double {
            var d = deg % 360.0
            if (d < 0) d += 360.0
            return d
        }

        /** Shortest signed angular difference a→b in (-180, 180]. */
        fun shortestDelta(fromDeg: Double, toDeg: Double): Double {
            var d = (toDeg - fromDeg) % 360.0
            if (d > 180.0) d -= 360.0
            if (d <= -180.0) d += 360.0
            return d
        }

        /** Map an Android SensorManager.SENSOR_STATUS_* accuracy to a confidence. */
        fun confidenceFromAccuracy(sensorStatusAccuracy: Int): HeadingConfidence =
            when {
                sensorStatusAccuracy >= 3 -> HeadingConfidence.HIGH
                sensorStatusAccuracy == 2 -> HeadingConfidence.MEDIUM
                sensorStatusAccuracy == 1 -> HeadingConfidence.LOW
                else -> HeadingConfidence.UNRELIABLE
            }
    }
}
