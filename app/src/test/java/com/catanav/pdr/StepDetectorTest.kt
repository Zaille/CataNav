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
    private fun walkingStream(steps: Int, peak: Double = 3.0): List<Double> {
        val out = mutableListOf<Double>()
        repeat(steps) {
            for (i in 0 until 15) out.add(peak * sin(PI * i / 14.0)) // 300 ms pulse
            repeat(15) { out.add(0.05) }                             // 300 ms quiet
        }
        return out
    }

    private fun feed(detector: StepDetector, stream: List<Double>): Int {
        var count = 0
        var t = 0L
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
    fun subThresholdJitter_countsNothing() {
        val d = StepDetector()
        val jitter = List(2000) { 0.4 * max(0.0, sin(it * 0.9)) + 0.3 }
        assertEquals(0, feed(d, jitter))
    }

    @Test
    fun violentShake_isRejectedByAmplitude() {
        val d = StepDetector()
        // 10 Hz shake at 25 m/s^2 for 4 seconds: every peak exceeds maxPeak.
        val shake = List(200) { 25.0 * max(0.0, sin(2 * PI * 10 * it * 0.02)) }
        assertEquals(0, feed(d, shake))
    }

    @Test
    fun rapidOscillation_isCappedByRefractoryPeriod() {
        val d = StepDetector()
        // Step-amplitude pulses but at 5 Hz — faster than any real cadence.
        // 5 seconds => 25 pulses; at >=300 ms per accepted step at most ~16 could
        // pass, and the refractory ratchet keeps the real count far lower.
        val out = mutableListOf<Double>()
        repeat(25) {
            for (i in 0 until 5) out.add(3.0 * sin(PI * i / 4.0)) // 100 ms pulse
            repeat(5) { out.add(0.0) }                            // 100 ms quiet
        }
        val counted = feed(d, out)
        assertTrue("counted=$counted — oscillation must not register as walking", counted <= 1)
    }

    @Test
    fun walkAfterShake_stillCounts() {
        val d = StepDetector()
        val shake = List(100) { 25.0 * max(0.0, sin(2 * PI * 10 * it * 0.02)) }
        feed(d, shake)
        // A second of stillness after the shake — the rejected shake peak resolves —
        // then normal walking must count fully again.
        feed(d, List(50) { 0.0 })
        val walked = feed(d, walkingStream(5))
        assertEquals(5, walked)
    }

    @Test
    fun reset_clearsCount() {
        val d = StepDetector()
        feed(d, walkingStream(3))
        d.reset()
        assertEquals(0, d.totalSteps)
    }
}
