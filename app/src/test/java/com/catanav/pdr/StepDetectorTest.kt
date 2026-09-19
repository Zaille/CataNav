package com.catanav.pdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Synthetic accelerometer streams at 50 Hz. Magnitude of TYPE_LINEAR_ACCELERATION is
 * always >= 0 (gravity already removed).
 */
class StepDetectorTest {

    private val sampleMs = 20L // 50 Hz

    /** N walking steps: a ~3 m/s^2 half-sine pulse (300 ms) then 300 ms of quiet. */
    private fun walkingStream(steps: Int, peak: Double = 3.0, floor: Double = 0.05): List<Double> {
        val out = mutableListOf<Double>()
        repeat(steps) {
            for (i in 0 until 15) out.add(floor + peak * sin(PI * i / 14.0)) // 300 ms pulse
            repeat(15) { out.add(floor) }                                    // 300 ms quiet
        }
        return out
    }

    /**
     * Realistic step at ~1.8 Hz (560 ms): heel strike (160 ms, [heel] m/s^2), short
     * dip, toe-off sub-peak (120 ms, [toe] m/s^2), then quiet — all riding on [floor].
     */
    private fun heelToeStream(steps: Int, heel: Double = 3.0, toe: Double = 1.8, floor: Double = 1.3): List<Double> {
        val out = mutableListOf<Double>()
        repeat(steps) {
            for (i in 0 until 8) out.add(floor + heel * sin(PI * i / 7.0))
            repeat(4) { out.add(floor) }
            for (i in 0 until 6) out.add(floor + toe * sin(PI * i / 5.0))
            repeat(10) { out.add(floor) }
        }
        return out
    }

    private fun feed(detector: StepDetector, stream: List<Double>, startMs: Long = 0L): Int {
        var count = 0
        var t = startMs
        for (m in stream) {
            if (detector.onSample(t, m)) count++
            t += sampleMs
        }
        return count
    }

    @Test
    fun cleanWalk_countsEveryStep() {
        val d = StepDetector()
        assertEquals(20, feed(d, walkingStream(20)))
        assertEquals(20, d.totalSteps)
    }

    @Test
    fun walkOnRaisedFloor_countsEveryStep() {
        // In the hand or a pocket the norm never comes back near zero between steps
        // (~1.3 m/s^2 of residual motion). The detector must still re-arm each step.
        val d = StepDetector()
        assertEquals(20, feed(d, walkingStream(20, floor = 1.3)))
    }

    @Test
    fun heelStrikeThenToeOff_countsOneStepPerStride() {
        val d = StepDetector()
        assertEquals(30, feed(d, heelToeStream(30)))
    }

    @Test
    fun pocketWalk_largeAmplitudeIsStillAStep() {
        // Phone in a trouser pocket or going down stairs: 12–15 m/s^2 peaks.
        val d = StepDetector()
        assertEquals(20, feed(d, walkingStream(20, peak = 14.0, floor = 2.0)))
    }

    @Test
    fun gentleWalk_smallAmplitudeIsStillAStep() {
        // Phone held steady in front while walking slowly: ~1.5 m/s^2 bumps.
        val d = StepDetector()
        assertEquals(20, feed(d, walkingStream(20, peak = 1.5, floor = 0.4)))
    }

    @Test
    fun stopAndGo_countsEverySegment() {
        val d = StepDetector()
        var total = 0
        var t = 0L
        repeat(4) {
            val walk = walkingStream(5)
            total += feed(d, walk, t)
            t += walk.size * sampleMs
            val rest = List(100) { 0.1 } // 2 s standing still
            total += feed(d, rest, t)
            t += rest.size * sampleMs
        }
        assertEquals(20, total)
    }

    @Test
    fun subThresholdJitter_countsNothing() {
        val d = StepDetector()
        val jitter = List(2000) { 0.4 * max(0.0, sin(it * 0.9)) + 0.3 }
        assertEquals(0, feed(d, jitter))
    }

    @Test
    fun standingStill_countsNothing() {
        val d = StepDetector()
        assertEquals(0, feed(d, List(3000) { 0.15 + 0.1 * sin(it * 0.37) }))
    }

    @Test
    fun violentShake_isRejectedByAmplitude() {
        val d = StepDetector()
        // 10 Hz shake at 45 m/s^2 for 4 seconds: every raw peak exceeds maxPeak.
        val shake = List(200) { 45.0 * max(0.0, sin(2 * PI * 10 * it * 0.02)) }
        assertEquals(0, feed(d, shake))
    }

    @Test
    fun rapidOscillation_isCappedByRefractoryPeriod() {
        val d = StepDetector()
        // Step-amplitude pulses at 5 Hz — faster than any real cadence. 25 pulses in
        // 5 s; the 300 ms refractory period allows at most every other one through.
        val out = mutableListOf<Double>()
        repeat(25) {
            for (i in 0 until 5) out.add(3.0 * sin(PI * i / 4.0)) // 100 ms pulse
            repeat(5) { out.add(0.0) }                            // 100 ms quiet
        }
        val counted = feed(d, out)
        assertTrue("counted=$counted — cadence must be capped by the refractory period", counted <= 13)
    }

    @Test
    fun walkAfterShake_stillCounts() {
        val d = StepDetector()
        val shake = List(100) { 45.0 * max(0.0, sin(2 * PI * 10 * it * 0.02)) }
        feed(d, shake)
        // A second of stillness after the shake — the baseline settles — then normal
        // walking must count fully again.
        feed(d, List(50) { 0.0 }, 100 * sampleMs)
        val walked = feed(d, walkingStream(5), 150 * sampleMs)
        assertEquals(5, walked)
    }

    @Test
    fun sensorPause_doesNotProduceAPhantomStep() {
        val d = StepDetector()
        feed(d, walkingStream(3))
        // Stream resumes 5 s later at a different level (phone picked up).
        val resumed = feed(d, List(50) { 2.0 }, 3 * 30 * sampleMs + 5000)
        assertEquals(0, resumed)
    }

    @Test
    fun reset_clearsCount() {
        val d = StepDetector()
        feed(d, walkingStream(3))
        d.reset()
        assertEquals(0, d.totalSteps)
    }
}
