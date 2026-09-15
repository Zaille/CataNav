package com.catanav.pdr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dead-reckoning position state in MAP-LOCAL METRIC coordinates (meters; X = east,
 * Y = north — see domain/Coordinates.kt for the space definitions). One update per
 * detected step, heading in degrees with 0° = north, clockwise:
 *
 *   newX = oldX + distance * sin(heading_rad)   // X = east
 *   newY = oldY + distance * cos(heading_rad)   // Y = north
 *
 * The engine knows NOTHING about pixels, map images, or calibrations — the
 * CoordinateTransformer converts at the rendering/export boundary only. There is also
 * deliberately NO GPS calibration path anywhere: its role is fully replaced by manual
 * re-anchoring ([anchor]) and the device's step-length calibration.
 */
class PdrEngine(
    @Volatile var stepLengthMeters: Double = DEFAULT_STEP_LENGTH_M,
) {
    data class Position(
        val xMeters: Double,
        val yMeters: Double,
        val headingDeg: Double,
        val distanceSinceAnchorM: Double,
        val totalSteps: Int,
        val isAnchor: Boolean,
    )

    private val _position = MutableStateFlow<Position?>(null)
    val position: StateFlow<Position?> = _position

    val isAnchored: Boolean get() = _position.value != null

    /**
     * Hard-set the position to a user-confirmed map point (meters). Trip start IS the
     * first anchor — the engine has no position at all until the first call.
     * Heading is NOT auto-corrected: pass [headingDeg] only when the user confirmed a
     * facing direction on the manual dial; null keeps the current heading estimate.
     */
    fun anchor(xMeters: Double, yMeters: Double, headingDeg: Double? = null) {
        val prev = _position.value
        _position.value = Position(
            xMeters = xMeters,
            yMeters = yMeters,
            headingDeg = headingDeg ?: prev?.headingDeg ?: Double.NaN,
            distanceSinceAnchorM = 0.0,
            totalSteps = prev?.totalSteps ?: 0,
            isAnchor = true,
        )
    }

    /**
     * Advance one step along [headingDeg] (true-north compass heading; any device
     * orientation offset is applied by the caller). [direction] is +1, or -1 for the
     * manual mode "step back" button. No-op until the first anchor exists.
     */
    fun onStep(headingDeg: Double, direction: Int = 1) {
        val prev = _position.value ?: return
        if (headingDeg.isNaN()) return
        val rad = Math.toRadians(headingDeg)
        val d = direction.coerceIn(-1, 1)
        _position.value = Position(
            xMeters = prev.xMeters + d * stepLengthMeters * sin(rad),
            yMeters = prev.yMeters + d * stepLengthMeters * cos(rad),
            headingDeg = HeadingEstimator.normalize(headingDeg),
            distanceSinceAnchorM = prev.distanceSinceAnchorM + stepLengthMeters,
            totalSteps = prev.totalSteps + 1,
            isAnchor = false,
        )
    }

    /** Update the rendered heading between steps (marker arrow follows the compass). */
    fun onHeadingChanged(headingDeg: Double) {
        val prev = _position.value ?: return
        if (headingDeg.isNaN()) return
        _position.value = prev.copy(headingDeg = HeadingEstimator.normalize(headingDeg), isAnchor = false)
    }

    /**
     * Rebuild in-memory state from persisted track points when resuming an interrupted
     * trip — unlike [anchor], distance-since-anchor and step count survive, so the
     * uncertainty circle comes back at its true size.
     */
    fun restore(
        xMeters: Double,
        yMeters: Double,
        headingDeg: Double,
        distanceSinceAnchorM: Double,
        totalSteps: Int,
    ) {
        _position.value = Position(
            xMeters = xMeters,
            yMeters = yMeters,
            headingDeg = headingDeg,
            distanceSinceAnchorM = distanceSinceAnchorM,
            totalSteps = totalSteps,
            isAnchor = false,
        )
    }

    fun reset() {
        _position.value = null
    }

    companion object {
        /** Rough adult default until the calibration screen has been run. */
        const val DEFAULT_STEP_LENGTH_M = 0.70
    }
}
