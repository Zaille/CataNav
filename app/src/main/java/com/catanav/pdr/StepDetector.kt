package com.catanav.pdr

/**
 * Peak detector over the magnitude of TYPE_LINEAR_ACCELERATION (gravity already
 * removed). Pure Kotlin — fed by SensorHub on device, by synthetic streams in tests.
 *
 * A step is a peak that:
 *  - rises above [minPeak] after the detector is armed,
 *  - whose maximum stays below [maxPeak] (larger spikes are shakes/impacts, not steps),
 *  - falls back below [minPeak] - [valleyDrop] (hysteresis re-arm),
 *  - and arrives at least [minStepIntervalMs] after the previous accepted step
 *    (caps cadence at ~3 Hz; faster oscillation is noise).
 *
 * The step fires on the FALLING edge so the peak amplitude can be validated first.
 */
class StepDetector(
    private val minPeak: Double = 1.6,
    private val maxPeak: Double = 12.0,
    private val valleyDrop: Double = 0.8,
    private val minStepIntervalMs: Long = 300,
    private val smoothingAlpha: Double = 0.5,
) {
    private var smoothed = 0.0
    private var initialized = false
    private var inPeak = false
    private var peakMax = 0.0
    private var lastStepAtMs = Long.MIN_VALUE

    var totalSteps: Int = 0
        private set

    /** Returns true when this sample completes a detected step. */
    fun onSample(timestampMs: Long, magnitude: Double): Boolean {
        smoothed = if (!initialized) {
            initialized = true
            magnitude
        } else {
            smoothingAlpha * magnitude + (1 - smoothingAlpha) * smoothed
        }

        if (!inPeak) {
            if (smoothed >= minPeak) {
                inPeak = true
                peakMax = smoothed
            }
            return false
        }

        if (smoothed > peakMax) peakMax = smoothed

        if (smoothed < minPeak - valleyDrop) {
            inPeak = false
            val amplitudeOk = peakMax <= maxPeak
            val intervalOk = lastStepAtMs == Long.MIN_VALUE ||
                timestampMs - lastStepAtMs >= minStepIntervalMs
            peakMax = 0.0
            if (amplitudeOk && intervalOk) {
                lastStepAtMs = timestampMs
                totalSteps++
                return true
            }
            // Rejected peak still updates the refractory clock when it looked like
            // rapid oscillation, so a shake burst cannot ratchet steps through.
            if (!intervalOk) lastStepAtMs = timestampMs
        }
        return false
    }

    fun reset() {
        smoothed = 0.0
        initialized = false
        inPeak = false
        peakMax = 0.0
        lastStepAtMs = Long.MIN_VALUE
        totalSteps = 0
    }
}
