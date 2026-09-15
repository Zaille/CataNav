package com.catanav.pdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class HeadingEstimatorTest {

    // ---- shared angle helpers ------------------------------------------------

    @Test
    fun shortestDelta_handlesWrapAround() {
        assertEquals(20.0, HeadingEstimator.shortestDelta(350.0, 10.0), 1e-9)
        assertEquals(-20.0, HeadingEstimator.shortestDelta(10.0, 350.0), 1e-9)
        assertEquals(180.0, HeadingEstimator.shortestDelta(0.0, 180.0), 1e-9)
        assertEquals(0.0, HeadingEstimator.shortestDelta(123.0, 123.0), 1e-9)
    }

    @Test
    fun normalize_wraps() {
        assertEquals(350.0, HeadingEstimator.normalize(-10.0), 1e-9)
        assertEquals(1.0, HeadingEstimator.normalize(361.0), 1e-9)
    }

    // ---- rotation vector ------------------------------------------------------

    private fun yawQuaternion(yawDeg: Double): FloatArray {
        // Rotation about device/world Z axis (device flat, screen up).
        val half = Math.toRadians(yawDeg) / 2.0
        return floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat())
    }

    @Test
    fun rotationVector_identity_isNorth() {
        val h = RotationVectorHeading()
        h.onRotationVector(yawQuaternion(0.0))
        assertEquals(0.0, h.headingDeg, 1e-6)
    }

    @Test
    fun rotationVector_deviceYawedCcw90_reads270() {
        // Rotating the device counter-clockwise (viewed from above) by 90 deg swings
        // the device Y axis from north to west => azimuth 270.
        val h = RotationVectorHeading()
        h.onRotationVector(yawQuaternion(90.0))
        // Tolerance bounded by float32 quaternion precision, not the math.
        assertEquals(270.0, h.headingDeg, 1e-4)
    }

    @Test
    fun rotationVector_deviceYawedCw90_reads90() {
        val h = RotationVectorHeading()
        h.onRotationVector(yawQuaternion(-90.0))
        assertEquals(90.0, h.headingDeg, 1e-4)
    }

    @Test
    fun rotationVector_threeComponentEvent_computesW() {
        // Older devices deliver only x,y,z; w must be reconstructed.
        val q = yawQuaternion(-45.0)
        val h3 = RotationVectorHeading()
        h3.onRotationVector(floatArrayOf(q[0], q[1], q[2]))
        val h4 = RotationVectorHeading()
        h4.onRotationVector(q)
        assertEquals(h4.headingDeg, h3.headingDeg, 1e-4)
        assertEquals(45.0, h3.headingDeg, 1e-4)
    }

    @Test
    fun rotationVector_accuracyLow_flagsUnreliable() {
        val h = RotationVectorHeading()
        assertTrue(h.isReliable)
        h.onAccuracyChanged(1) // SENSOR_STATUS_ACCURACY_LOW
        assertFalse(h.isReliable)
        h.onAccuracyChanged(3) // HIGH
        assertTrue(h.isReliable)
    }

    // ---- complementary filter ---------------------------------------------------

    private val flatAccel = floatArrayOf(0f, 0f, 9.81f)

    /** Magnetic field in device coords for a flat device facing [headingDeg]. */
    private fun magFor(headingDeg: Double): FloatArray {
        // World field: horizontal 30 uT pointing north, vertical -40 uT (down).
        val rad = Math.toRadians(headingDeg)
        val north = 30.0
        return floatArrayOf(
            (-north * sin(rad)).toFloat(),
            (north * cos(rad)).toFloat(),
            -40f,
        )
    }

    @Test
    fun complementary_flatDeviceFacingNorth_reads0() {
        val h = ComplementaryFilterHeading()
        h.onAccelerometer(flatAccel)
        h.onMagnetometer(magFor(0.0))
        assertEquals(0.0, h.headingDeg, 1e-6)
    }

    @Test
    fun complementary_flatDeviceFacingEast_reads90() {
        val h = ComplementaryFilterHeading()
        h.onAccelerometer(flatAccel)
        h.onMagnetometer(magFor(90.0))
        assertEquals(90.0, h.headingDeg, 1e-6)
    }

    @Test
    fun complementary_gyroIntegration_tracksClockwiseTurn() {
        // alpha = 1.0 => pure gyro after the initial compass seed.
        val h = ComplementaryFilterHeading(alpha = 1.0)
        h.onAccelerometer(flatAccel)
        h.onMagnetometer(magFor(0.0)) // seed at 0
        // Clockwise turn (viewed from above, screen up) = negative wz.
        // 90 deg over 1 s, sampled at 100 Hz.
        val wz = -PI / 2
        var t = 1_000_000_000L
        h.onGyro(t, wz)
        repeat(100) {
            t += 10_000_000L // 10 ms
            h.onGyro(t, wz)
        }
        assertEquals(90.0, h.headingDeg, 0.5)
    }

    @Test
    fun complementary_blendConvergesTowardCompass() {
        val h = ComplementaryFilterHeading(alpha = 0.9)
        h.onAccelerometer(flatAccel)
        h.onMagnetometer(magFor(0.0)) // seed at 0
        h.onMagnetometer(magFor(30.0)) // compass now says 30
        var t = 1_000_000_000L
        h.onGyro(t, 0.0)
        repeat(200) {
            t += 10_000_000L
            h.onGyro(t, 0.0) // stationary: only the blend acts
        }
        assertEquals(30.0, h.headingDeg, 1.0)
    }

    @Test
    fun complementary_blendCrossesZeroSeamCorrectly() {
        val h = ComplementaryFilterHeading(alpha = 0.9)
        h.onAccelerometer(flatAccel)
        h.onMagnetometer(magFor(350.0)) // seed at 350
        h.onMagnetometer(magFor(10.0))  // compass swings across north
        var t = 0L
        repeat(200) {
            t += 10_000_000L
            h.onGyro(t, 0.0)
        }
        // Must converge to 10 going through 0/360, not around through 180.
        assertEquals(10.0, h.headingDeg, 1.0)
    }

    @Test
    fun complementary_absurdFieldMagnitude_flagsUnreliable() {
        val h = ComplementaryFilterHeading()
        h.onAccelerometer(flatAccel)
        h.onMagnetometer(floatArrayOf(150f, 150f, 150f)) // rebar territory
        assertFalse(h.isReliable)
        h.onMagnetometer(magFor(0.0))
        assertTrue(h.isReliable)
    }

    @Test
    fun complementary_magnetometerAccuracyLow_flagsUnreliable() {
        val h = ComplementaryFilterHeading()
        h.onMagnetometerAccuracyChanged(0) // SENSOR_STATUS_UNRELIABLE
        assertFalse(h.isReliable)
    }

    // ---- v2: graded confidence ---------------------------------------------------

    @Test
    fun rotationVector_confidenceFollowsAccuracy() {
        val h = RotationVectorHeading()
        assertEquals(HeadingConfidence.HIGH, h.confidence)
        h.onAccuracyChanged(2)
        assertEquals(HeadingConfidence.MEDIUM, h.confidence)
        assertTrue(h.isReliable)
        h.onAccuracyChanged(1)
        assertEquals(HeadingConfidence.LOW, h.confidence)
        assertFalse(h.isReliable)
        h.onAccuracyChanged(0)
        assertEquals(HeadingConfidence.UNRELIABLE, h.confidence)
    }

    @Test
    fun complementary_fieldMagnitudeOverridesAccuracy() {
        val h = ComplementaryFilterHeading()
        h.onMagnetometerAccuracyChanged(3)
        h.onAccelerometer(flatAccel)
        h.onMagnetometer(floatArrayOf(150f, 150f, 150f)) // rebar territory
        assertEquals(HeadingConfidence.UNRELIABLE, h.confidence)
        h.onMagnetometer(magFor(0.0)) // sane field again
        assertEquals(HeadingConfidence.HIGH, h.confidence)
    }

    @Test
    fun confidence_mapsToHeadingUncertainty() {
        assertEquals(5.0, HeadingConfidence.HIGH.headingUncertaintyDegrees, 0.0)
        assertEquals(90.0, HeadingConfidence.UNRELIABLE.headingUncertaintyDegrees, 0.0)
    }
}
