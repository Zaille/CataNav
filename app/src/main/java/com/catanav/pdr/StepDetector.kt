package com.catanav.pdr

/**
 * Peak detector over the magnitude of TYPE_LINEAR_ACCELERATION (gravity already
 * removed). Pure Kotlin — fed by SensorHub on device, by synthetic streams in tests.
 *
 * The magnitude is a norm, so it never goes negative and, while walking, rarely comes
 * back down near zero between two steps (all three axes carry noise and the phone
 * swings). Detecting on the raw norm with absolute thresholds therefore misses most
 * steps: the detector never re-arms. Instead the signal is centred first:
 *
 *  - [smoothed]  : fast EMA of the norm (time constant [fastTauMs]) to merge the
 *                  heel-strike / toe-off sub-peaks of one step into one bump;
 *  - [baseline]  : slow EMA of [smoothed] tracking the DC level. It falls faster than
 *                  it rises ([baselineDownTauMs] < [baselineUpTauMs]) so it hugs the
 *                  troughs while walking and drops back quickly after a burst;
 *  - hp = smoothed - baseline oscillates around zero during walking.
 *
 * A step is a bump of hp that:
 *  - rises above [minPeak],
 *  - falls back below [rearmRatio] * peak (hysteresis relative to the bump height,
 *    so small sub-peaks riding on a big step do not re-arm the detector),
 *  - whose RAW norm never exceeded [maxPeak] since the previous re-arm (drops and
 *    impacts, not steps),
 *  - and whose peak arrives at least [minStepIntervalMs] after the previous accepted
 *    step (caps cadence at ~3.3 Hz). A bump rejected by this rule is simply ignored —
 *    it does NOT push the refractory clock, otherwise a toe-off sub-peak would keep
 *    postponing the next real heel strike and half the steps would be lost.
 *
 * The step fires on the FALLING edge so the bump can be validated first. All time
 * constants are in milliseconds and use the sample timestamps, so behaviour does not
 * depend on the device's actual sensor rate.
 */
class StepDetector(
    private val minPeak: Double = 1.0,
    private val maxPeak: Double = 35.0,
    private val rearmRatio: Double = 0.35,
    private val minStepIntervalMs: Long = 300,
    private val fastTauMs: Double = 40.0,
    private val baselineUpTauMs: Double = 1000.0,
    private val baselineDownTauMs: Double = 400.0,
) {
    private var smoothed = 0.0
    private var baseline = 0.0
    private var initialized = false
    private var lastSampleMs = 0L
    private var inPeak = false
    private var peakMax = 0.0
    private var peakAtMs = 0L
    private var rawMaxSinceRearm = 0.0
    private var lastStepAtMs = Long.MIN_VALUE

    var totalSteps: Int = 0
        private set

    /** Returns true when this sample completes a detected step. */
    fun onSample(timestampMs: Long, magnitude: Double): Boolean {
        val gapMs = timestampMs - lastSampleMs
        if (!initialized || gapMs > RESTART_GAP_MS) {
            // First sample, or the sensor stream was paused: start from the current level.
            initialized = true
            smoothed = magnitude
            baseline = magnitude
            inPeak = false
            peakMax = 0.0
            rawMaxSinceRearm = magnitude
            lastSampleMs = timestampMs
            return false
        }
        val dt = gapMs.coerceIn(1L, 200L).toDouble()
        lastSampleMs = timestampMs

        smoothed += (dt / (fastTauMs + dt)) * (magnitude - smoothed)
        val baselineTau = if (smoothed < baseline) baselineDownTauMs else baselineUpTauMs
        baseline += (dt / (baselineTau + dt)) * (smoothed - baseline)
        val hp = smoothed - baseline

        if (magnitude > rawMaxSinceRearm) rawMaxSinceRearm = magnitude

        if (!inPeak) {
            if (hp >= minPeak) {
                inPeak = true
                peakMax = hp
                peakAtMs = timestampMs
            }
            return false
        }

        if (hp > peakMax) {
            peakMax = hp
            peakAtMs = timestampMs
        }

        if (hp < peakMax * rearmRatio) {
            inPeak = false
            val amplitudeOk = rawMaxSinceRearm <= maxPeak
            val intervalOk = lastStepAtMs == Long.MIN_VALUE ||
                peakAtMs - lastStepAtMs >= minStepIntervalMs
            peakMax = 0.0
            rawMaxSinceRearm = magnitude
            if (amplitudeOk && intervalOk) {
                lastStepAtMs = peakAtMs
                totalSteps++
                return true
            }
        }
        return false
    }

    fun reset() {
        smoothed = 0.0
        baseline = 0.0
        initialized = false
        lastSampleMs = 0L
        inPeak = false
        peakMax = 0.0
        peakAtMs = 0L
        rawMaxSinceRearm = 0.0
        lastStepAtMs = Long.MIN_VALUE
        totalSteps = 0
    }

    private companion object {
        /** A gap this long means the sensor was paused; re-seed the filters. */
        const val RESTART_GAP_MS = 1000L
    }
}
