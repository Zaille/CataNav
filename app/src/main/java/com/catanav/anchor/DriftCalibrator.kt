package com.catanav.anchor

import kotlin.math.hypot

/**
 * Adaptive drift-rate estimate for the uncertainty circle:
 *
 *   radius_m = distance_since_anchor_m * driftRate
 *
 * Starts at [DEFAULT_RATE]; on every re-anchor the delta between the predicted position
 * and the user-tapped position, divided by the distance walked since the last anchor,
 * is one drift sample. A running average over all samples (persisted per-device across
 * trips via SettingsStore) refines the estimate — every real trip becomes calibration
 * data. Resettable from Settings.
 */
class DriftCalibrator(
    rate: Double = DEFAULT_RATE,
    sampleCount: Int = 0,
) {
    var rate: Double = rate.coerceIn(MIN_RATE, MAX_RATE)
        private set
    var sampleCount: Int = sampleCount.coerceAtLeast(0)
        private set

    fun uncertaintyRadiusMeters(distanceSinceAnchorM: Double): Double =
        distanceSinceAnchorM * rate

    /**
     * Record a re-anchor correction. [predictedX/YM] and [actualX/YM] in METERS (any
     * consistent frame); [distanceWalkedM] since the previous anchor. Corrections after
     * very short walks are ignored — the tap error would dominate the drift signal.
     * Returns true when the sample was accepted.
     */
    fun onReanchor(
        predictedXM: Double,
        predictedYM: Double,
        actualXM: Double,
        actualYM: Double,
        distanceWalkedM: Double,
    ): Boolean {
        if (distanceWalkedM < MIN_DISTANCE_M) return false
        val errorM = hypot(actualXM - predictedXM, actualYM - predictedYM)
        val sample = (errorM / distanceWalkedM).coerceIn(MIN_RATE, MAX_RATE)
        rate = ((rate * sampleCount + sample) / (sampleCount + 1)).coerceIn(MIN_RATE, MAX_RATE)
        sampleCount++
        return true
    }

    fun reset() {
        rate = DEFAULT_RATE
        sampleCount = 0
    }

    companion object {
        const val DEFAULT_RATE = 0.05
        const val MIN_RATE = 0.01
        const val MAX_RATE = 0.50
        /** Below this walked distance a correction is anchor-tap noise, not drift. */
        const val MIN_DISTANCE_M = 10.0
    }
}
