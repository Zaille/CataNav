package com.catanav.domain

import kotlin.math.abs
import kotlin.math.hypot

/** Pure math behind the calibration flows — unit-tested, no Android types. */
object CalibrationMath {

    /** Two points must be at least this far apart on the image to calibrate scale. */
    const val MIN_PIXEL_DISTANCE = 20.0

    /**
     * Two-point calibration: metersPerPixel = realDistanceMeters / imageDistancePixels.
     * Throws on degenerate input (same point, zero/negative distance).
     */
    fun metersPerPixel(a: ImagePoint, b: ImagePoint, realDistanceMeters: Double): Double {
        require(realDistanceMeters > 0) { "real-world distance must be > 0" }
        val px = hypot(b.xPx - a.xPx, b.yPx - a.yPx)
        require(px >= MIN_PIXEL_DISTANCE) {
            "points are only $px px apart — zoom in and pick two distinct landmarks"
        }
        return realDistanceMeters / px
    }

    /**
     * North-arrow step: the user rotates an on-screen arrow until it points to true
     * north AS DRAWN ON THE MAP. [arrowScreenDeg] is the arrow angle clockwise from
     * screen-up; [viewRotationDeg] is the map view's display-only rotation at that
     * moment. Stored convention (see Coordinates.kt): degrees clockwise from IMAGE-up
     * to north — so the view rotation must be subtracted out.
     */
    fun northOffsetFromArrow(arrowScreenDeg: Double, viewRotationDeg: Double): Double {
        var d = (arrowScreenDeg - viewRotationDeg) % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /** Gating: a map with no calibrated active version cannot start a trip. */
    fun canStartTrip(activeVersionId: Long?): Boolean = activeVersionId != null

    /**
     * Calibration-test screen: % error between the PDR-estimated distance and the
     * known walked distance. Positive = PDR overestimates.
     */
    fun testErrorPercent(knownDistanceM: Double, estimatedDistanceM: Double): Double {
        require(knownDistanceM > 0) { "known distance must be > 0" }
        return (estimatedDistanceM - knownDistanceM) / knownDistanceM * 100.0
    }

    /** Convenience for the test screen: estimated distance from steps x step length. */
    fun estimatedDistance(steps: Int, stepLengthM: Double): Double =
        abs(steps) * stepLengthM
}
