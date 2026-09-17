package com.catanav.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationMathTest {

    // ---- two-point scale -----------------------------------------------------------

    @Test
    fun metersPerPixel_derivation() {
        // 231 px apart, 100 m real -> 0.4329... m/px (e.g. cave survey scale).
        val mpp = CalibrationMath.metersPerPixel(
            ImagePoint(1000.0, 1000.0), ImagePoint(1231.0, 1000.0), 100.0,
        )
        assertEquals(100.0 / 231.0, mpp, 1e-12)
    }

    @Test
    fun metersPerPixel_usesEuclideanDistance() {
        // 3-4-5 triangle: 300 px horizontal, 400 px vertical = 500 px apart, 100 m.
        val mpp = CalibrationMath.metersPerPixel(
            ImagePoint(0.0, 0.0), ImagePoint(300.0, 400.0), 100.0,
        )
        assertEquals(0.2, mpp, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun metersPerPixel_rejectsPointsTooClose() {
        CalibrationMath.metersPerPixel(ImagePoint(0.0, 0.0), ImagePoint(3.0, 4.0), 100.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun metersPerPixel_rejectsNonPositiveDistance() {
        CalibrationMath.metersPerPixel(ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 0.0)
    }

    // ---- north offset storage convention ---------------------------------------------

    @Test
    fun northOffset_arrowOnUnrotatedView_isStoredVerbatim() {
        assertEquals(37.5, CalibrationMath.northOffsetFromArrow(37.5, 0.0), 1e-12)
        assertEquals(0.0, CalibrationMath.northOffsetFromArrow(0.0, 0.0), 1e-12)
    }

    @Test
    fun northOffset_viewRotationIsSubtractedOut() {
        // The user rotated the view by 90 and set the arrow to 100 on screen: the
        // image-space offset (locked convention) is 10.
        assertEquals(10.0, CalibrationMath.northOffsetFromArrow(100.0, 90.0), 1e-12)
        // Wraps into [0, 360).
        assertEquals(350.0, CalibrationMath.northOffsetFromArrow(10.0, 20.0), 1e-12)
    }

    // ---- trip gating ----------------------------------------------------------------------

    @Test
    fun canStartTrip_onlyWithActiveVersion() {
        assertFalse(CalibrationMath.canStartTrip(null))
        assertTrue(CalibrationMath.canStartTrip(42L))
    }

    // ---- calibration-test error math -------------------------------------------------------

    @Test
    fun testErrorPercent_signedError() {
        assertEquals(5.0, CalibrationMath.testErrorPercent(100.0, 105.0), 1e-12)
        assertEquals(-10.0, CalibrationMath.testErrorPercent(50.0, 45.0), 1e-12)
        assertEquals(0.0, CalibrationMath.testErrorPercent(30.0, 30.0), 1e-12)
    }

    @Test
    fun estimatedDistance_stepsTimesStepLength() {
        assertEquals(35.0, CalibrationMath.estimatedDistance(50, 0.7), 1e-12)
    }
}
