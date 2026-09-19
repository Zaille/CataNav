package com.catanav.anchor

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Learns the step length from re-anchors.
 *
 * Between two anchors the engine predicts a displacement P (dead reckoning from the
 * previous anchor to the predicted position); the user's tap gives the true
 * displacement A. Projecting A onto P separates the two error sources:
 *
 *  - the ALONG-track component (dot(A, P) / |P|^2) is the ratio by which the walked
 *    distance was under- or over-estimated => the step-length error;
 *  - the CROSS-track component is heading error and says nothing about stride.
 *
 * The projection only means something when the leg was walked roughly in a straight
 * line: on a winding path heading and stride errors mix. A leg is accepted when the
 * straight-line predicted displacement is at least [minStraightness] of the distance
 * actually walked, at least [minDistanceM] were walked (tap error otherwise dominates)
 * and the cross-track error is not absurd ([maxLateralRatio] of |P|).
 *
 * Each accepted sample moves the step length by [gain] of the measured error, with the
 * per-sample ratio clamped to [[minRatio], [maxRatio]] so one bad tap cannot wreck the
 * calibration; the result is clamped to a plausible human stride.
 */
class StrideCalibrator(
    private val minDistanceM: Double = 15.0,
    private val minStraightness: Double = 0.8,
    private val maxLateralRatio: Double = 0.5,
    private val gain: Double = 0.35,
    private val minRatio: Double = 0.7,
    private val maxRatio: Double = 1.3,
    private val minStepM: Double = MIN_STEP_M,
    private val maxStepM: Double = MAX_STEP_M,
) {
    data class Adjustment(val beforeM: Double, val afterM: Double, val measuredRatio: Double)

    /**
     * All coordinates in meters, same frame. Returns the corrected step length, or null
     * when this leg is not usable as a stride sample.
     */
    fun onReanchor(
        anchorXM: Double,
        anchorYM: Double,
        predictedXM: Double,
        predictedYM: Double,
        actualXM: Double,
        actualYM: Double,
        distanceWalkedM: Double,
        currentStepLengthM: Double,
    ): Adjustment? {
        if (distanceWalkedM < minDistanceM) return null
        val px = predictedXM - anchorXM
        val py = predictedYM - anchorYM
        val predictedLen = hypot(px, py)
        if (predictedLen <= 0.0) return null
        if (predictedLen / distanceWalkedM < minStraightness) return null

        val ax = actualXM - anchorXM
        val ay = actualYM - anchorYM
        val along = (ax * px + ay * py) / (predictedLen * predictedLen)
        val lateral = abs(ax * py - ay * px) / (predictedLen * predictedLen)
        if (lateral > maxLateralRatio) return null
        if (along <= 0.0) return null // tapped behind the anchor: not a straight leg

        val ratio = along.coerceIn(minRatio, maxRatio)
        val after = (currentStepLengthM * (1.0 + gain * (ratio - 1.0))).coerceIn(minStepM, maxStepM)
        return Adjustment(beforeM = currentStepLengthM, afterM = after, measuredRatio = along)
    }

    companion object {
        const val MIN_STEP_M = 0.3
        const val MAX_STEP_M = 1.5
    }
}
